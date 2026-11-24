package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.EventPublicationRegistry;

/**
 * 定期の再投入が数える {@code event.publication.resubmissions} と、再投入の結果の Counter を、DB の行を直接動かして検証する。
 *
 * <p>リスナーは {@code @Async} で別のスレッドで動くため、{@link FailedEventPublications} のモックが、本物の {@code resubmit}
 * と同じく FAILED の行を読んで条件の関数を適用し、通った行を {@code markResubmitted} と同じ更新で {@code RESUBMITTED}
 * に進める。リスナーの完了と再失敗は、テストが行を直接動かして作る。ジョブは テストごとの {@link SimpleMeterRegistry} で作り、Spring の文脈は {@link
 * EventPublicationResubmitterRunsTest} と同じ設定で共有する。
 *
 * <p>古い出版を FAILED に戻す処理は、ふつうは何もしないモックにし、その処理が戻した出版の数え方を確かめるテストだけ本物にする。
 */
// 行の準備、リスナーの完了と再失敗の再現、Counter の読み出しを同じ文脈で扱うため、メソッドの数が多い。
@SuppressWarnings("PMD.TooManyMethods")
@ResubmissionTestSupport.SharedContext
class EventPublicationResubmitterOutcomesTest {

  /** 出版の時刻。マイクロ秒の精度にする。待ち時間を十分に過ぎた時刻でジョブを動かす。 */
  private static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** 方針を持たない listener_id で、default の方針が使われる。 */
  private static final String LISTENER_ID = "outcomes-test";

  /** 再投入の候補にする FAILED の状態。 */
  private static final String FAILED = "FAILED";

  /** 再投入した出版の状態。 */
  private static final String RESUBMITTED = "RESUBMITTED";

  /** 再投入の結果の Counter の名前。 */
  private static final String OUTCOMES = "event.publication.resubmission.outcomes";

  /** 完了した結果の outcome。 */
  private static final String OUTCOME_COMPLETED = "completed";

  /** 再び失敗した結果の outcome。 */
  private static final String OUTCOME_FAILED = "failed";

  /** 再投入した出版の数の Counter の名前。 */
  private static final String RESUBMISSIONS = "event.publication.resubmissions";

  /** 実際のロックの実行。 */
  @Autowired private LockingTaskExecutor lockingTaskExecutor;

  /** リスナーの方針。 */
  @Autowired private ResubmissionPolicies policies;

  /** 設定値。 */
  @Autowired private EventPublicationResubmissionConfig.Properties properties;

  /** 再投入の回数の上限。 */
  @Autowired private EventPublicationResubmissionConfig.AbandonProperties abandon;

  /** 差し替えられる時計。 */
  @Autowired private EventPublicationResubmitterTest.MutableClock clock;

  /** Spring Modulith の、古い出版を FAILED に戻す処理。時計は {@link #clock} である。 */
  @Autowired private EventPublicationRegistry registry;

  /** 出版の行を動かす DB。 */
  @Autowired private DSLContext dsl;

  /** テストごとのレジストリ。 */
  private SimpleMeterRegistry meterRegistry;

  /** {@code markResubmitted} が偽になる（他の更新が先に行を動かした）出版の ID。 */
  private final Set<UUID> notMarked = ConcurrentHashMap.newKeySet();

  @BeforeEach
  void reset() {
    meterRegistry = new SimpleMeterRegistry();
    notMarked.clear();
    clock.set(T0.plusSeconds(3600));
    expireLock();
  }

  /** 拡張の後始末は RESUBMITTED の行がなくなるまで待つため、その前に行を消す。 */
  @AfterEach
  void deleteRows() {
    dsl.deleteFrom(EVENT_PUBLICATION).execute();
    dsl.deleteFrom(EVENT_PUBLICATION_ARCHIVE).execute();
  }

  @Test
  @DisplayName("resubmissions は RESUBMITTED に進んだ出版だけを数え、markResubmitted が偽だった行は数えない")
  void resubmissionsCountsOnlyPublicationsThatMoved() {
    final UUID moved = insert(FAILED, 0);
    final UUID alreadyMoved = insert(FAILED, 0);
    notMarked.add(alreadyMoved);

    newResubmitter(properties).resubmitOnce();

    assertThat(attempts(moved)).isEqualTo(1);
    assertThat(attempts(alreadyMoved)).isZero();
    assertThat(count(RESUBMISSIONS)).isEqualTo(1.0);

    final EventPublicationResubmitter resubmitter = newResubmitter(properties);
    resubmitter.resubmitOnce();
    run(resubmitter);
    assertThat(outcome(OUTCOME_FAILED)).as("進まなかった行は結果の対象にしない").isZero();
  }

  @Test
  @DisplayName("resubmissions は max-per-run で止まった件数だけ増え、残りは次の回に数える")
  void resubmissionsStopsAtMaxPerRun() {
    insert(FAILED, 0);
    insert(FAILED, 0);
    insert(FAILED, 0);
    final EventPublicationResubmitter resubmitter =
        newResubmitter(
            new EventPublicationResubmissionConfig.Properties(
                properties.waitAge(),
                properties.inFlightStaleness(),
                properties.interval(),
                2,
                properties.lockAtMostFor(),
                properties.policies()));

    resubmitter.resubmitOnce();

    assertThat(count(RESUBMISSIONS)).isEqualTo(2.0);

    expireLock();
    resubmitter.resubmitOnce();

    assertThat(count(RESUBMISSIONS)).as("残りの 1 件は次の回").isEqualTo(3.0);
  }

  @Test
  @DisplayName("再投入の前に完了して archive に移った出版も、進んだものとして数える")
  void publicationCompletedDuringResubmissionIsCounted() {
    final UUID id = insert(FAILED, 0);
    final EventPublicationResubmitter resubmitter =
        ResubmissionTestSupport.resubmitter(
            dsl,
            failedPublications(() -> {
              move(id, RESUBMITTED);
              complete(id);
            }),
            lockingTaskExecutor,
            clock,
            properties,
            policies,
            meterRegistry,
            abandon);

    resubmitter.resubmitOnce();

    assertThat(count(RESUBMISSIONS)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("再投入した出版の処理中は数えず、完了した次の回に completed を 1 だけ数える（終端の状態は期限に関係しない）")
  void completedIsCountedOnceAfterProcessing() {
    final UUID id = insert(FAILED, 0);
    final EventPublicationResubmitter resubmitter = newResubmitter(properties);

    resubmitter.resubmitOnce();
    run(resubmitter);
    run(resubmitter);

    assertThat(status(id)).isEqualTo(RESUBMITTED);
    assertThat(outcome(OUTCOME_COMPLETED)).as("処理中").isZero();
    assertThat(outcome(OUTCOME_FAILED)).isZero();

    complete(id);
    clock.set(clock.instant().plus(Duration.ofDays(1)));
    run(resubmitter);
    run(resubmitter);

    assertThat(outcome(OUTCOME_COMPLETED)).as("完了した後は 1 回だけ").isEqualTo(1.0);
    assertThat(outcome(OUTCOME_FAILED)).isZero();
  }

  @Test
  @DisplayName("再投入した出版が再び失敗して FAILED に戻ったら failed を 1 だけ数える")
  void refailureIsCountedOnce() {
    final UUID id = insert(FAILED, 0);
    final EventPublicationResubmitter resubmitter = newResubmitter(properties);
    resubmitter.resubmitOnce();

    move(id, FAILED);
    run(resubmitter);
    run(resubmitter);

    assertThat(outcome(OUTCOME_FAILED)).isEqualTo(1.0);
    assertThat(outcome(OUTCOME_COMPLETED)).isZero();
  }

  @Test
  @DisplayName(
      "再投入した出版が回数の上限を超えて ABANDONED で archive に移ったら、completed でなく failed と exhausted を 1 だけ数える")
  void abandonedIsCountedAsFailedAndExhaustedOnce() {
    final UUID id = insert(FAILED, 1);
    final EventPublicationResubmitter resubmitter = newResubmitter(properties);
    resubmitter.resubmitOnce();

    archive(id, "ABANDONED");
    run(resubmitter);
    run(resubmitter);

    assertThat(outcome(OUTCOME_FAILED)).isEqualTo(1.0);
    assertThat(outcome(OUTCOME_COMPLETED)).isZero();
    assertThat(count("event.publication.resubmission.exhausted")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("覚えた出版の行が消えたら、数えずに忘れ、後で同じ ID が archive に現れても数えない")
  void deletedPublicationIsForgottenWithoutCounting() {
    final UUID id = insert(FAILED, 0);
    final EventPublicationResubmitter resubmitter = newResubmitter(properties);
    resubmitter.resubmitOnce();

    dsl.deleteFrom(EVENT_PUBLICATION).where(EVENT_PUBLICATION.ID.eq(id)).execute();
    run(resubmitter);
    insert(id, RESUBMITTED, 1);
    complete(id);
    run(resubmitter);

    assertThat(outcome(OUTCOME_COMPLETED)).isZero();
    assertThat(outcome(OUTCOME_FAILED)).isZero();
  }

  @Test
  @DisplayName(
      "未完了のまま覚える期間（max(3 × lock-at-most-for, in-flight-staleness + lock-at-most-for + 2 ×"
          + " interval)）ちょうどでは覚え、1 マイクロ秒過ぎると捨てる")
  void unfinishedPublicationExpiresAfterLifetime() {
    final Duration lifetime = ResubmissionTracker.lifetimeOf(properties);
    assertThat(lifetime)
        .as("in-flight-staleness + lock-at-most-for + 2 × interval が lock-at-most-for × 3 より長い")
        .isEqualTo(
            properties
                .inFlightStaleness()
                .plus(properties.lockAtMostFor())
                .plus(properties.interval().multipliedBy(2)));
    final UUID kept = insert(FAILED, 0);
    final EventPublicationResubmitter keeping = newResubmitter(properties);
    final Instant resubmittedAt = clock.instant();
    keeping.resubmitOnce();

    clock.set(resubmittedAt.plus(lifetime));
    run(keeping);
    complete(kept);
    run(keeping);

    assertThat(outcome(OUTCOME_COMPLETED)).as("期間ちょうどでは覚えている").isEqualTo(1.0);

    clock.set(resubmittedAt);
    meterRegistry = new SimpleMeterRegistry();
    final UUID dropped = insert(FAILED, 0);
    final EventPublicationResubmitter dropping = newResubmitter(properties);
    run(dropping);

    clock.set(resubmittedAt.plus(lifetime).plus(1, ChronoUnit.MICROS));
    run(dropping);
    complete(dropped);
    run(dropping);

    assertThat(outcome(OUTCOME_COMPLETED)).as("期間を過ぎたら捨てている").isZero();
  }

  @Test
  @DisplayName("別のインスタンスが再投入した出版は、自分の Counter に数えない")
  void otherInstancePublicationIsNotCounted() {
    final UUID id = insert(FAILED, 0);
    final EventPublicationResubmitter first = newResubmitter(properties);
    final SimpleMeterRegistry otherRegistry = new SimpleMeterRegistry();
    final EventPublicationResubmitter second =
        ResubmissionTestSupport.resubmitter(
            dsl,
            failedPublications(() -> {}),
            lockingTaskExecutor,
            clock,
            properties,
            policies,
            otherRegistry,
            abandon);
    first.resubmitOnce();
    complete(id);

    run(second);
    run(first);

    assertThat(otherRegistry.get(OUTCOMES).tag("outcome", OUTCOME_COMPLETED).counter().count())
        .as("再投入していないインスタンス")
        .isZero();
    assertThat(outcome(OUTCOME_COMPLETED)).as("再投入したインスタンス").isEqualTo(1.0);
  }

  @Test
  @DisplayName("処理中のまま残った再投入の出版を、同じインスタンスが古い出版として FAILED に戻して同じ回に再投入し直したら、前の再投入を failed に 1 だけ数える")
  void staleResubmissionResubmittedAgainByTheSameInstanceIsCountedFailed() {
    final UUID id = insertReadable();
    final EventPublicationResubmitter resubmitter = withStaleCheck(meterRegistry);
    resubmitter.resubmitOnce();

    clock.set(pastInFlightStaleness());
    run(resubmitter);
    complete(id);
    run(resubmitter);

    assertThat(count(RESUBMISSIONS)).as("古い出版として戻した回の再投入も数える").isEqualTo(2.0);
    assertThat(outcome(OUTCOME_FAILED)).as("処理中のまま残った前の再投入").isEqualTo(1.0);
    assertThat(outcome(OUTCOME_COMPLETED)).as("再投入し直した後の完了").isEqualTo(1.0);
  }

  @Test
  @DisplayName(
      "処理中のまま残った再投入の出版を別のインスタンスが古い出版として FAILED に戻して再投入し直したら、前に再投入したインスタンスは failed に、"
          + "再投入し直したインスタンスは結果に数える")
  void staleResubmissionResubmittedAgainByOtherInstanceIsCountedFailedByTheFirst() {
    final UUID id = insertReadable();
    final EventPublicationResubmitter first = withStaleCheck(meterRegistry);
    final SimpleMeterRegistry otherRegistry = new SimpleMeterRegistry();
    final EventPublicationResubmitter second = withStaleCheck(otherRegistry);
    first.resubmitOnce();

    clock.set(pastInFlightStaleness());
    run(second);
    complete(id);
    run(first);
    run(second);

    assertThat(outcome(OUTCOME_FAILED)).as("前に再投入したインスタンスの failed").isEqualTo(1.0);
    assertThat(outcome(OUTCOME_COMPLETED)).as("前に再投入したインスタンスの completed").isZero();
    assertThat(otherRegistry.get(OUTCOMES).tag("outcome", OUTCOME_COMPLETED).counter().count())
        .as("再投入し直したインスタンスの completed")
        .isEqualTo(1.0);
  }

  private EventPublicationResubmitter newResubmitter(
      final EventPublicationResubmissionConfig.Properties jobProperties) {
    return ResubmissionTestSupport.resubmitter(
        dsl,
        failedPublications(() -> {}),
        lockingTaskExecutor,
        clock,
        jobProperties,
        policies,
        meterRegistry,
        abandon);
  }

  /** 古い出版を FAILED に戻す処理を Spring Modulith の本物にしたジョブ。 */
  private EventPublicationResubmitter withStaleCheck(final SimpleMeterRegistry jobMeterRegistry) {
    return ResubmissionTestSupport.resubmitter(
        dsl,
        failedPublications(() -> {}),
        lockingTaskExecutor,
        clock,
        properties,
        policies,
        jobMeterRegistry,
        abandon,
        registry);
  }

  /** 今の時刻を、in-flight-staleness より 1 マイクロ秒だけ後にした時刻。今の時刻に再投入した出版が古くなる。 */
  private Instant pastInFlightStaleness() {
    return clock.instant().plus(properties.inFlightStaleness()).plus(1, ChronoUnit.MICROS);
  }

  /**
   * 本物の {@code resubmit} と同じく、FAILED の行を読んで条件の関数を適用し、通った行を {@code markResubmitted} と同じ更新で {@code
   * RESUBMITTED} に進めるモック。{@code afterEach} は、行を進めたあとにテストが行を動かす場所。
   */
  private FailedEventPublications failedPublications(final Runnable afterEach) {
    final FailedEventPublications failed = Mockito.mock(FailedEventPublications.class);
    Mockito.doAnswer(
            invocation -> {
              final java.util.function.Predicate<EventPublication> filter =
                  invocation.<ResubmissionOptions>getArgument(0).getFilter();
              for (final Record3<UUID, Integer, Instant> row :
                  dsl.select(
                          EVENT_PUBLICATION.ID,
                          EVENT_PUBLICATION.COMPLETION_ATTEMPTS,
                          EVENT_PUBLICATION.PUBLICATION_DATE)
                      .from(EVENT_PUBLICATION)
                      .where(EVENT_PUBLICATION.STATUS.eq(FAILED))
                      .orderBy(EVENT_PUBLICATION.PUBLICATION_DATE)
                      .fetch()) {
                final EventPublication publication =
                    ResubmissionTestSupport.stubPublication(
                        Mockito.mock(EventPublication.class, Mockito.CALLS_REAL_METHODS),
                        row.value1(),
                        row.value2(),
                        row.value3(),
                        null);
                if (filter.test(publication) && !notMarked.contains(row.value1())) {
                  move(row.value1(), RESUBMITTED);
                  afterEach.run();
                }
              }
              return null;
            })
        .when(failed)
        .resubmit(Mockito.any(ResubmissionOptions.class));
    return failed;
  }

  /** {@code markResubmitted} と同じ更新で状態を動かす。FAILED への戻しは回数を増やさない。 */
  private void move(final UUID id, final String status) {
    dsl.update(EVENT_PUBLICATION)
        .set(EVENT_PUBLICATION.STATUS, status)
        .set(
            EVENT_PUBLICATION.COMPLETION_ATTEMPTS,
            RESUBMITTED.equals(status)
                ? EVENT_PUBLICATION.COMPLETION_ATTEMPTS.plus(1)
                : EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
        .set(
            EVENT_PUBLICATION.LAST_RESUBMISSION_DATE,
            RESUBMITTED.equals(status)
                ? DSL.inline(clock.instant())
                : EVENT_PUBLICATION.LAST_RESUBMISSION_DATE)
        .where(EVENT_PUBLICATION.ID.eq(id))
        .execute();
  }

  /** リスナーの完了と同じく、行を archive へ移す。 */
  private void complete(final UUID id) {
    archive(id, "COMPLETED");
  }

  /** 完了か諦めと同じく、行を {@code status} で archive へ移す。 */
  private void archive(final UUID id, final String status) {
    dsl.insertInto(EVENT_PUBLICATION_ARCHIVE)
        .columns(
            EVENT_PUBLICATION_ARCHIVE.ID,
            EVENT_PUBLICATION_ARCHIVE.LISTENER_ID,
            EVENT_PUBLICATION_ARCHIVE.EVENT_TYPE,
            EVENT_PUBLICATION_ARCHIVE.SERIALIZED_EVENT,
            EVENT_PUBLICATION_ARCHIVE.PUBLICATION_DATE,
            EVENT_PUBLICATION_ARCHIVE.STATUS,
            EVENT_PUBLICATION_ARCHIVE.COMPLETION_DATE,
            EVENT_PUBLICATION_ARCHIVE.COMPLETION_ATTEMPTS)
        .select(
            dsl.select(
                    EVENT_PUBLICATION.ID,
                    EVENT_PUBLICATION.LISTENER_ID,
                    EVENT_PUBLICATION.EVENT_TYPE,
                    EVENT_PUBLICATION.SERIALIZED_EVENT,
                    EVENT_PUBLICATION.PUBLICATION_DATE,
                    DSL.inline(status),
                    DSL.inline(clock.instant()),
                    EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
                .from(EVENT_PUBLICATION)
                .where(EVENT_PUBLICATION.ID.eq(id)))
        .execute();
    dsl.deleteFrom(EVENT_PUBLICATION).where(EVENT_PUBLICATION.ID.eq(id)).execute();
  }

  /** 古い出版を FAILED に戻す処理が読み戻せるよう、テストのイベントの型で FAILED の出版の行を作る。 */
  private UUID insertReadable() {
    return ResubmissionTestSupport.insertPublication(
        dsl,
        UUID.randomUUID(),
        LISTENER_ID,
        EventPublicationResubmitterTest.ResubmissionProbe.class.getName(),
        T0,
        FAILED,
        0);
  }

  private UUID insert(final String status, final int completionAttempts) {
    return insert(UUID.randomUUID(), status, completionAttempts);
  }

  private UUID insert(final UUID id, final String status, final int completionAttempts) {
    return ResubmissionTestSupport.insertPublication(
        dsl, id, LISTENER_ID, T0, status, completionAttempts);
  }

  private int attempts(final UUID id) {
    return Objects.requireNonNull(
        dsl.select(EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.ID.eq(id))
            .fetchSingle(EVENT_PUBLICATION.COMPLETION_ATTEMPTS),
        "completion_attempts");
  }

  private String status(final UUID id) {
    return Objects.requireNonNull(
        dsl.select(EVENT_PUBLICATION.STATUS)
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.ID.eq(id))
            .fetchSingle(EVENT_PUBLICATION.STATUS),
        "status");
  }

  /** ロックの期限を過去にしてから、ジョブを 1 回動かす。 */
  private void run(final EventPublicationResubmitter resubmitter) {
    expireLock();
    resubmitter.resubmitOnce();
  }

  private double outcome(final String outcome) {
    return meterRegistry.get(OUTCOMES).tag("outcome", outcome).counter().count();
  }

  private double count(final String name) {
    return meterRegistry.get(name).counter().count();
  }

  private void expireLock() {
    ResubmissionTestSupport.expireLock(dsl);
  }
}
