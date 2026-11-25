package com.example.demo.payment.idempotencycheck;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.payment.idempotencycheck.IdempotencyProbes.AccumulatingListener;
import com.example.demo.payment.idempotencycheck.IdempotencyProbes.ChangingCallListener;
import com.example.demo.payment.idempotencycheck.IdempotencyProbes.ForUpdateListener;
import com.example.demo.payment.idempotencycheck.IdempotencyProbes.LockNoUpdateListener;
import com.example.demo.payment.idempotencycheck.IdempotencyProbes.NoopListener;
import com.example.demo.payment.idempotencycheck.IdempotencyProbes.OnConflictListener;
import com.example.demo.payment.idempotencycheck.IdempotencyProbes.PlainInsertListener;
import com.example.demo.payment.idempotencycheck.IdempotencyProbes.UncoveredListener;
import com.example.demo.payment.idempotencycheck.IdempotencyProbes.UnlockedSelectListener;
import com.example.demo.testkit.CommittedDatabaseTest;
import com.example.demo.testkit.idempotency.ListenerExample;
import com.example.demo.testkit.idempotency.ListenerId;
import com.example.demo.testkit.idempotency.ListenerIdempotencyChecker;
import com.example.demo.testkit.idempotency.Violation;
import com.example.demo.testkit.idempotency.Violation.Kind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;

/**
 * 冪等性の検査（{@link ListenerIdempotencyChecker}）が、わざと違反するリスナーの違反を見つけ、通るべきリスナーを通すことを確かめる。
 *
 * <p>違反するリスナーは {@link IdempotencyProbes} にあり、この文脈だけに載せる。
 */
@CommittedDatabaseTest
@EnableScenarios
@Import({ListenerIdempotencyChecker.Config.class, IdempotencyProbes.Config.class})
class ListenerIdempotencyCheckerTest {

  /** ロックを取らない SELECT のリスナーの同時の 2 回を繰り返す回数。 */
  private static final int RACE_REPEATS = 10;

  /** 検査。 */
  @Autowired private ListenerIdempotencyChecker checker;

  /** 自己検査の入力例。 */
  @Autowired private List<ListenerExample> examples;

  @Test
  @DisplayName("入力例のないリスナーと、リスナーのない入力例を、リスナーの ID を挙げて見つける")
  void coverageFindsMissingAndOrphanExamples() {
    assertThat(checker.checkCoverage())
        .anyMatch(v ->
            v.kind() == Kind.NO_EXAMPLE
                && v.listener().equals(ListenerId.forListener(UncoveredListener.class)))
        .anyMatch(v ->
            v.kind() == Kind.ORPHAN_EXAMPLE
                && v.listener().equals(IdempotencyProbes.Config.MISSING));
  }

  @Test
  @DisplayName("届くたびに値を足すリスナーは、行の数が同じでも、既定でないテナントの行の変化で順の 2 回目の違反になる")
  void accumulatingListenerChangesRowsOnSecondDelivery(final Scenario scenario) {
    final List<Violation> violations =
        checker.checkSequential(scenario, example(AccumulatingListener.class));

    assertThat(violations)
        .extracting(Violation::kind)
        .containsExactly(Kind.SECOND_DELIVERY_CHANGED_ROWS);
    assertThat(violations.getFirst().detail())
        .contains("payment.t_payment tenant=" + ListenerIdempotencyChecker.OTHER_TENANT)
        .contains("removed=[{")
        .contains("added=[{");
  }

  @Test
  @DisplayName("何も書かない入力例は、1 回目の配信が何も変えない違反になる")
  void noopExampleChangesNothing(final Scenario scenario) {
    assertThat(checker.checkSequential(scenario, example(NoopListener.class)))
        .extracting(Violation::kind)
        .containsExactly(Kind.FIRST_DELIVERY_CHANGED_NOTHING);
  }

  @Test
  @DisplayName("2 回目に例外で止まるリスナーは、出版が完了しない違反になる")
  void failingSecondDeliveryLeavesPublicationIncomplete(final Scenario scenario) {
    final List<Violation> violations =
        checker.checkSequential(scenario, example(PlainInsertListener.class));

    assertThat(violations)
        .extracting(Violation::kind)
        .containsExactly(Kind.PUBLICATION_NOT_COMPLETED);
    assertThat(violations.getFirst().detail()).contains("second delivery").contains("FAILED=1");
  }

  @Test
  @DisplayName("ロックを取らない SELECT で状態を見るリスナーは、順の 2 回を通り、同時の 2 回で毎回違反になる")
  void unlockedSelectFailsEveryConcurrentRace(final Scenario scenario) {
    final ListenerExample example = example(UnlockedSelectListener.class);
    assertThat(checker.checkSequential(scenario, example)).as("順の 2 回").isEmpty();

    for (int i = 1; i <= RACE_REPEATS; i++) {
      assertThat(checker.checkConcurrent(scenario, example))
          .as("同時の 2 回の %d 回目", i)
          .extracting(Violation::kind)
          .containsExactly(Kind.CONCURRENT_RESULT_DIFFERS);
    }
  }

  @Test
  @DisplayName("2 回目に別の引数で外部システムを呼ぶリスナーは、順の 2 回でも同時の 2 回でも違反になる")
  void changingExternalCallIsDetected(final Scenario scenario) {
    final ListenerExample example = example(ChangingCallListener.class);

    assertThat(checker.checkSequential(scenario, example))
        .as("順の 2 回")
        .extracting(Violation::kind)
        .containsExactly(Kind.EXTERNAL_CALLS_DIFFER);
    assertThat(checker.checkConcurrent(scenario, example))
        .as("同時の 2 回")
        .extracting(Violation::kind)
        .containsExactly(Kind.EXTERNAL_CALLS_DIFFER);
  }

  @Test
  @DisplayName(
      "lock_no の WHERE の UPDATE、SELECT ... FOR UPDATE の後の判定、INSERT ... ON CONFLICT DO NOTHING は、順の 2 回も同時の 2 回も通る")
  void serializingListenersPass(final Scenario scenario) {
    for (final Class<?> listener :
        List.of(LockNoUpdateListener.class, ForUpdateListener.class, OnConflictListener.class)) {
      final ListenerExample example = example(listener);

      assertThat(checker.checkSequential(scenario, example))
          .as("%s の順の 2 回", listener.getSimpleName())
          .isEmpty();
      assertThat(checker.checkConcurrent(scenario, example))
          .as("%s の同時の 2 回", listener.getSimpleName())
          .isEmpty();
    }
  }

  private ListenerExample example(final Class<?> listener) {
    final ListenerId id = ListenerId.forListener(listener);
    return examples.stream()
        .filter(example -> example.listener().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no example for " + id));
  }
}
