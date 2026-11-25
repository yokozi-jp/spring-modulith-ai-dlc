package com.example.demo.payment.idempotencycheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.demo.payment.idempotencycheck.SerializingProbes.ForUpdateListener;
import com.example.demo.payment.idempotencycheck.SerializingProbes.LockNoUpdateListener;
import com.example.demo.payment.idempotencycheck.SerializingProbes.OnConflictListener;
import com.example.demo.payment.idempotencycheck.ViolatingProbes.AbandoningInsertListener;
import com.example.demo.payment.idempotencycheck.ViolatingProbes.AccumulatingListener;
import com.example.demo.payment.idempotencycheck.ViolatingProbes.ChangingCallListener;
import com.example.demo.payment.idempotencycheck.ViolatingProbes.FailingSecondListener;
import com.example.demo.payment.idempotencycheck.ViolatingProbes.NonTransactionalListener;
import com.example.demo.payment.idempotencycheck.ViolatingProbes.NoopListener;
import com.example.demo.payment.idempotencycheck.ViolatingProbes.OrphanExample;
import com.example.demo.payment.idempotencycheck.ViolatingProbes.PlainInsertListener;
import com.example.demo.payment.idempotencycheck.ViolatingProbes.StalledSecondListener;
import com.example.demo.payment.idempotencycheck.ViolatingProbes.UncoveredListener;
import com.example.demo.payment.idempotencycheck.ViolatingProbes.UnlockedSelectListener;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.CommittedDatabaseTest;
import com.example.demo.testkit.MigrationRoleConnections;
import com.example.demo.testkit.idempotency.ListenerExample;
import com.example.demo.testkit.idempotency.ListenerId;
import com.example.demo.testkit.idempotency.ListenerIdempotencyChecker;
import com.example.demo.testkit.idempotency.ListenerIdempotencyConfig;
import com.example.demo.testkit.idempotency.Violation;
import com.example.demo.testkit.idempotency.Violation.Kind;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

/**
 * 冪等性の検査（{@link ListenerIdempotencyChecker}）が、わざと違反するリスナーの違反を見つけ、通るべきリスナーを通すことを確かめる。
 *
 * <p>違反するリスナーは {@link ViolatingProbes} に、通るべきリスナーは {@link SerializingProbes} にあり、この文脈だけに載せる。
 */
@CommittedDatabaseTest
@Import({
  ListenerIdempotencyConfig.class,
  AccumulatingListener.class,
  UncoveredListener.class,
  OrphanExample.class,
  NoopListener.class,
  NonTransactionalListener.class,
  PlainInsertListener.class,
  AbandoningInsertListener.class,
  FailingSecondListener.class,
  StalledSecondListener.class,
  UnlockedSelectListener.class,
  ChangingCallListener.class,
  LockNoUpdateListener.class,
  ForUpdateListener.class,
  OnConflictListener.class
})
@TestPropertySource(
    properties = {
      "event-publication.resubmission.policies."
          + ViolatingProbes.ABANDON_AT_FIRST
          + ".multiplier=2",
      "event-publication.resubmission.policies."
          + ViolatingProbes.ABANDON_AT_FIRST
          + ".max-wait=PT1H",
      "event-publication.resubmission.policies."
          + ViolatingProbes.ABANDON_AT_FIRST
          + ".abandon-after-attempts=1"
    })
// 検査が見つける違反の種類と競合の分かれ道ごとに、1 つのテストを置くため。
@SuppressWarnings("PMD.TooManyMethods")
class ListenerIdempotencyCheckerTest {

  /** 順の 2 回の検査の結果の説明。 */
  private static final String SEQUENTIAL = "順の 2 回";

  /** 同時の 2 回の検査の結果の説明。 */
  private static final String CONCURRENT = "同時の 2 回";

  /** ロックを取らない SELECT のリスナーの同時の 2 回を繰り返す回数。 */
  private static final int RACE_REPEATS = 10;

  /** 無関係なロックの待ちを作る advisory lock のキー。 */
  private static final long UNRELATED_LOCK = 551L;

  /** 無関係なロックの待ちが始まるのを待つ上限。 */
  private static final Duration WAIT_START_LIMIT = Duration.ofSeconds(5);

  /** 検査。 */
  @Autowired private ListenerIdempotencyChecker checker;

  /** 自己検査の入力例。 */
  @Autowired private List<ListenerExample> examples;

  /** 2 回目を止めるリスナー。テストの後に通す。 */
  @Autowired private StalledSecondListener stalled;

  /** 所有者の接続の設定。 */
  @Autowired private Environment environment;

  @AfterEach
  void resumeStalledDelivery() {
    stalled.resume();
  }

  @Test
  @DisplayName("入力例のないリスナーと、リスナーのない入力例を、リスナーの ID を挙げて見つける")
  void coverageFindsMissingAndOrphanExamples() {
    assertThat(checker.checkCoverage())
        .anyMatch(v ->
            v.kind() == Kind.NO_EXAMPLE
                && v.listener().equals(ListenerId.forListener(UncoveredListener.class)))
        .anyMatch(
            v -> v.kind() == Kind.ORPHAN_EXAMPLE && v.listener().equals(OrphanExample.MISSING));
  }

  @Test
  @DisplayName("届くたびに値を足すリスナーは、行の数が同じでも、既定でないテナントの行の変化で順の 2 回目の違反になる")
  void accumulatingListenerChangesRowsOnSecondDelivery() {
    final List<Violation> violations = checker.checkSequential(example(AccumulatingListener.class));

    assertThat(violations)
        .extracting(Violation::kind)
        .containsExactly(Kind.SECOND_DELIVERY_CHANGED_ROWS);
    assertThat(violations.getFirst().detail())
        .contains("payment.t_payment tenant=" + AsTenant.OTHER_TENANT.value())
        .contains("removed=[{")
        .contains("added=[{");
  }

  @Test
  @DisplayName("何も書かない入力例は、1 回目の配信が何も変えない違反になる")
  void noopExampleChangesNothing() {
    assertThat(checker.checkSequential(example(NoopListener.class)))
        .extracting(Violation::kind)
        .containsExactly(Kind.FIRST_DELIVERY_CHANGED_NOTHING);
  }

  @Test
  @DisplayName("2 回目に例外で止まり出版が FAILED に残るリスナーは、配信の失敗の違反になる")
  void failingSecondDeliveryLeavesPublicationFailed() {
    final List<Violation> violations = checker.checkSequential(example(PlainInsertListener.class));

    assertThat(violations).extracting(Violation::kind).containsExactly(Kind.DELIVERY_FAILED);
    assertThat(violations.getFirst().detail()).contains("second delivery").contains("FAILED=1");
  }

  @Test
  @DisplayName("1 回目のロックを待った同時の 2 回目が一意制約の違反で終わるリスナーは、配信の失敗の違反になる")
  void concurrentSecondDeliveryFailingIsDetected() {
    final List<Violation> violations = checker.checkConcurrent(example(PlainInsertListener.class));

    assertThat(violations).extracting(Violation::kind).containsExactly(Kind.DELIVERY_FAILED);
    assertThat(violations.getFirst().detail()).contains("waited for a lock held by the first");
  }

  @Test
  @DisplayName("方針で最初の失敗の出版を諦めるリスナーの 2 回目の失敗は、archive に ABANDONED で移っても、順でも同時でも配信の失敗の違反になる")
  void abandonedSecondDeliveryIsAFailure() {
    final ListenerExample example = example(AbandoningInsertListener.class);

    final List<Violation> sequential = checker.checkSequential(example);
    assertThat(sequential)
        .as(SEQUENTIAL)
        .extracting(Violation::kind)
        .containsExactly(Kind.DELIVERY_FAILED);
    assertThat(sequential.getFirst().detail()).as(SEQUENTIAL).contains("ABANDONED=1");
    assertThat(checker.checkConcurrent(example))
        .as(CONCURRENT)
        .extracting(Violation::kind)
        .containsExactly(Kind.DELIVERY_FAILED);
  }

  @Test
  @DisplayName("1 回目がコミットする前に例外で終わった 2 回目は、競合とみなして 1 回目を通し、配信の失敗の違反になる")
  void secondFailingBeforeFirstCommitsIsDetected() {
    final List<Violation> violations =
        checker.checkConcurrent(example(FailingSecondListener.class));

    assertThat(violations).extracting(Violation::kind).containsExactly(Kind.DELIVERY_FAILED);
    assertThat(violations.getFirst().detail()).contains("failed before the first committed");
  }

  @Test
  @DisplayName("ロックも門にも届かず失敗もしない 2 回目は、競合を作れない違反になり、合格にならない")
  void secondNeitherWaitingNorReachingGateIsNotContended() {
    assertThat(checker.checkConcurrent(example(StalledSecondListener.class)))
        .extracting(Violation::kind)
        .containsExactly(Kind.SECOND_NOT_CONTENDED);
  }

  @Test
  @DisplayName("ロックを取らずに門に届いた 2 回目も競合とみなし、両方をコミットして比べる")
  void secondReachingGateWithoutLockIsCompared() {
    assertThat(checker.checkConcurrent(example(NoopListener.class))).isEmpty();
  }

  @Test
  @DisplayName("1 回目と無関係なロックの待ちを 2 回目の待ちとみなさず、ロックも門にも届かない 2 回目を、競合を作れない違反にする")
  void unrelatedLockWaitIsNotTakenForContention() throws SQLException {
    try (Connection holder = MigrationRoleConnections.open(environment);
        Connection waiter = MigrationRoleConnections.open(environment)) {
      holder.setAutoCommit(false);
      waiter.setAutoCommit(false);
      lockUnrelated(holder);
      final int waiterPid =
          DSL.using(waiter, SQLDialect.POSTGRES)
              .select(DSL.function(DSL.name("pg_backend_pid"), Integer.class))
              .fetchSingle()
              .value1();
      final CompletableFuture<Void> waiting =
          CompletableFuture.runAsync(() -> lockUnrelated(waiter));
      try {
        await("pid " + waiterPid + " waits for the unrelated advisory lock")
            .atMost(WAIT_START_LIMIT)
            .untilAsserted(() ->
                assertThat(waitsForLock(holder, waiterPid))
                    .as("pg_locks の granted = false")
                    .isTrue());

        assertThat(checker.checkConcurrent(example(StalledSecondListener.class)))
            .extracting(Violation::kind)
            .containsExactly(Kind.SECOND_NOT_CONTENDED);
      } finally {
        holder.rollback();
        waiting.join();
        waiter.rollback();
      }
    }
  }

  @Test
  @DisplayName("トランザクションの外で動くリスナーは、コミットの直前で止められず、競合を作れない違反になり、合格にならない")
  void listenerOutsideTransactionIsNotHeld() {
    final List<Violation> violations =
        checker.checkConcurrent(example(NonTransactionalListener.class));

    assertThat(violations).extracting(Violation::kind).containsExactly(Kind.FIRST_NOT_HELD);
    assertThat(violations.getFirst().detail()).contains("outsideTransaction=1");
  }

  @Test
  @DisplayName("ロックを取らない SELECT で状態を見るリスナーは、順の 2 回を通り、同時の 2 回で毎回違反になる")
  void unlockedSelectFailsEveryConcurrentRace() {
    final ListenerExample example = example(UnlockedSelectListener.class);
    assertThat(checker.checkSequential(example)).as(SEQUENTIAL).isEmpty();

    for (int i = 1; i <= RACE_REPEATS; i++) {
      assertThat(checker.checkConcurrent(example))
          .as("同時の 2 回の %d 回目", i)
          .extracting(Violation::kind)
          .containsExactly(Kind.CONCURRENT_RESULT_DIFFERS);
    }
  }

  @Test
  @DisplayName("2 回目に別の引数で外部システムを呼ぶリスナーは、順の 2 回でも同時の 2 回でも違反になる")
  void changingExternalCallIsDetected() {
    final ListenerExample example = example(ChangingCallListener.class);

    assertThat(checker.checkSequential(example))
        .as(SEQUENTIAL)
        .extracting(Violation::kind)
        .containsExactly(Kind.EXTERNAL_CALLS_DIFFER);
    assertThat(checker.checkConcurrent(example))
        .as(CONCURRENT)
        .extracting(Violation::kind)
        .containsExactly(Kind.EXTERNAL_CALLS_DIFFER);
  }

  @ParameterizedTest
  @ValueSource(
      classes = {LockNoUpdateListener.class, ForUpdateListener.class, OnConflictListener.class})
  @DisplayName(
      "lock_no の WHERE の TableWriter.update、SELECT ... FOR UPDATE の後の判定、INSERT ... ON CONFLICT DO NOTHING は、順の 2 回も同時の 2 回も通る")
  void serializingListenersPass(final Class<?> listener) {
    final ListenerExample example = example(listener);

    assertThat(checker.checkSequential(example)).as(SEQUENTIAL).isEmpty();
    assertThat(checker.checkConcurrent(example)).as(CONCURRENT).isEmpty();
  }

  /** {@link #UNRELATED_LOCK} の advisory lock を、接続のトランザクションの終わりまで取る。取れるまで待つ。 */
  private static void lockUnrelated(final Connection connection) {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SELECT pg_advisory_xact_lock(" + UNRELATED_LOCK + ")");
    } catch (SQLException e) {
      throw new IllegalStateException("failed to take the unrelated advisory lock", e);
    }
  }

  /** セッション {@code pid} が、取れていないロックを待っているか。 */
  private static boolean waitsForLock(final Connection connection, final int pid) {
    return DSL.using(connection, SQLDialect.POSTGRES)
        .fetchExists(
            DSL.table(DSL.name("pg_catalog", "pg_locks")),
            DSL.field(DSL.name("pid"), Integer.class)
                .eq(pid)
                .and(DSL.field(DSL.name("granted"), Boolean.class).isFalse()));
  }

  private ListenerExample example(final Class<?> listener) {
    final ListenerId id = ListenerId.forListener(listener);
    return examples.stream()
        .filter(example -> example.listener().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no example for " + id));
  }
}
