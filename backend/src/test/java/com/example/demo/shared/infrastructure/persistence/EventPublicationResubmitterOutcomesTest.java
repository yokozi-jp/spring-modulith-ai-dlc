package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.jooq.modulith.Tables;
import com.example.demo.testkit.CapturedLogRecords;
import com.example.demo.testkit.CommittedDatabaseTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.test.context.TestPropertySource;

/**
 * 定期の再投入が数える {@code event.publication.resubmissions} と、再投入の結果の Counter を、DB の行を直接動かして検証する。
 *
 * <p>リスナーは {@code @Async} で別のスレッドで動くため、{@link FailedEventPublications} のモックが、本物の {@code resubmit}
 * と同じく FAILED の行を読んで条件の関数を適用し、通った行を {@code markResubmitted} と同じ更新で {@code RESUBMITTED}
 * に進める。リスナーの完了と再失敗は、テストが行を直接動かして作る。ジョブは テストごとの {@link SimpleMeterRegistry} で作り、Spring の文脈は {@link
 * EventPublicationResubmitterRunsTest} と同じ設定で共有する。
 */
// 行の準備、リスナーの完了と再失敗の再現、Counter の読み出しを同じ文脈で扱うため、メソッドの数が多い。
@SuppressWarnings("PMD.TooManyMethods")
@CommittedDatabaseTest
@Import(EventPublicationResubmitterTest.ProbeConfiguration.class)
@TestPropertySource(
    properties = {
      "event-publication.resubmission.wait-age=PT5M",
      "event-publication.resubmission.interval=PT2S",
      "event-publication.resubmission.lock-at-most-for=PT3S",
      "event-publication.resubmission.max-per-run=100",
      "event-publication.resubmission.policies.default.max-resubmissions=1",
      "event-publication.resubmission.policies.probe.max-resubmissions=2",
      "event-publication.resubmission.policies.probe.multiplier=2",
      "event-publication.resubmission.policies.probe.max-wait=PT20M"
    })
class EventPublicationResubmitterOutcomesTest {

  /** 出版の時刻。マイクロ秒の精度にする。待ち時間を十分に過ぎた時刻でジョブを動かす。 */
  private static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** 方針を持たない listener_id で、default（max-resubmissions=1）が使われる。 */
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

  /** 上限に達した出版の Counter の名前。 */
  private static final String EXHAUSTED = "event.publication.resubmission.exhausted";

  /** 上限に達した出版の ERROR の本文。 */
  private static final String EXHAUSTED_MESSAGE = "Event publication resubmission exhausted";

  /** 再投入した出版の数の Counter の名前。 */
  private static final String RESUBMISSIONS = "event.publication.resubmissions";

  /** 実際のロックの実行。 */
  @Autowired private LockingTaskExecutor lockingTaskExecutor;

  /** リスナーの方針。 */
  @Autowired private ResubmissionPolicies policies;

  /** 設定値。 */
  @Autowired private EventPublicationResubmissionConfig.Properties properties;

  /** 差し替えられる時計。 */
  @Autowired private EventPublicationResubmitterTest.MutableClock clock;

  /** OTLP へ送る LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

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
        new EventPublicationResubmitter(
            dsl,
            failedPublications(
                () -> {
                  move(id, RESUBMITTED);
                  complete(id);
                }),
            lockingTaskExecutor,
            policies,
            clock,
            meterRegistry,
            properties);

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
    assertThat(count(EXHAUSTED)).isZero();
  }

  @Test
  @DisplayName("再投入した出版が上限未満で再び失敗したら failed を 1 だけ数え、exhausted は数えない")
  void refailureIsCountedOnce() {
    final UUID id = insert(FAILED, 0);
    final EventPublicationResubmitter resubmitter = newResubmitter(properties);
    resubmitter.resubmitOnce();

    move(id, FAILED);
    run(resubmitter);
    run(resubmitter);

    assertThat(outcome(OUTCOME_FAILED)).isEqualTo(1.0);
    assertThat(outcome(OUTCOME_COMPLETED)).isZero();
    assertThat(count(EXHAUSTED)).isZero();
    assertThat(exhaustedErrors(id)).isEmpty();
  }

  @Test
  @DisplayName("再投入した出版が再び失敗して上限に達したら failed と exhausted を 1 だけ数え、ERROR も 1 回だけ記録する")
  void exhaustionIsCountedAndReportedOnce() {
    final UUID id = insert(FAILED, 1);
    // interval が 1 時間なら毎回の実行が回収も行うので、回収と読み戻しが同じ出版を重ねて記録しないことも確かめる。
    final EventPublicationResubmitter resubmitter =
        newResubmitter(
            new EventPublicationResubmissionConfig.Properties(
                properties.waitAge(),
                Duration.ofHours(1),
                properties.maxPerRun(),
                Duration.ofHours(2),
                properties.policies()));
    resubmitter.resubmitOnce();
    assertThat(attempts(id)).isEqualTo(2);

    move(id, FAILED);
    run(resubmitter);
    run(resubmitter);
    run(resubmitter);

    assertThat(outcome(OUTCOME_FAILED)).isEqualTo(1.0);
    assertThat(count(EXHAUSTED)).isEqualTo(1.0);
    assertThat(exhaustedErrors(id)).as("複数回の実行で増えない").hasSize(1);
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
  @DisplayName("未完了のまま覚える期間（max(3 × lock-at-most-for, wait-age + interval)）ちょうどでは覚え、1 マイクロ秒過ぎると捨てる")
  void unfinishedPublicationExpiresAfterLifetime() {
    final Duration lifetime = ResubmissionTracker.lifetimeOf(properties);
    assertThat(lifetime)
        .as("wait-age + interval が lock-at-most-for × 3 より長い")
        .isEqualTo(Duration.ofSeconds(302));
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
        new EventPublicationResubmitter(
            dsl,
            failedPublications(() -> {}),
            lockingTaskExecutor,
            policies,
            clock,
            otherRegistry,
            properties);
    first.resubmitOnce();
    complete(id);

    run(second);
    run(first);

    assertThat(otherRegistry.get(OUTCOMES).tag("outcome", OUTCOME_COMPLETED).counter().count())
        .as("再投入していないインスタンス")
        .isZero();
    assertThat(outcome(OUTCOME_COMPLETED)).as("再投入したインスタンス").isEqualTo(1.0);
  }

  private EventPublicationResubmitter newResubmitter(
      final EventPublicationResubmissionConfig.Properties jobProperties) {
    return new EventPublicationResubmitter(
        dsl,
        failedPublications(() -> {}),
        lockingTaskExecutor,
        policies,
        clock,
        meterRegistry,
        jobProperties);
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
                final EventPublication publication = Mockito.mock(EventPublication.class);
                Mockito.when(publication.getIdentifier()).thenReturn(row.value1());
                Mockito.when(publication.getCompletionAttempts()).thenReturn(row.value2());
                Mockito.when(publication.getPublicationDate()).thenReturn(row.value3());
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
                    DSL.inline("COMPLETED"),
                    DSL.inline(clock.instant()),
                    EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
                .from(EVENT_PUBLICATION)
                .where(EVENT_PUBLICATION.ID.eq(id)))
        .execute();
    dsl.deleteFrom(EVENT_PUBLICATION).where(EVENT_PUBLICATION.ID.eq(id)).execute();
  }

  private UUID insert(final String status, final int completionAttempts) {
    return insert(UUID.randomUUID(), status, completionAttempts);
  }

  private UUID insert(final UUID id, final String status, final int completionAttempts) {
    dsl.insertInto(EVENT_PUBLICATION)
        .set(EVENT_PUBLICATION.ID, id)
        .set(EVENT_PUBLICATION.LISTENER_ID, LISTENER_ID)
        .set(EVENT_PUBLICATION.EVENT_TYPE, "test.event")
        .set(EVENT_PUBLICATION.SERIALIZED_EVENT, "{}")
        .set(EVENT_PUBLICATION.PUBLICATION_DATE, T0)
        .set(EVENT_PUBLICATION.STATUS, status)
        .set(EVENT_PUBLICATION.COMPLETION_ATTEMPTS, completionAttempts)
        .execute();
    return id;
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

  private List<LogRecordData> exhaustedErrors(final UUID id) {
    return capturedLogRecords.withBody(EXHAUSTED_MESSAGE).stream()
        .filter(
            record ->
                id.toString()
                    .equals(
                        record.getAttributes().get(AttributeKey.stringKey("event_publication.id"))))
        .toList();
  }

  private double count(final String name) {
    return meterRegistry.get(name).counter().count();
  }

  /** ロックの期限を過去にする。期限は DB の時刻で決まり、固定の時計では進まないため。 */
  private void expireLock() {
    dsl.update(Tables.SHEDLOCK)
        .set(Tables.SHEDLOCK.LOCK_UNTIL, Tables.SHEDLOCK.LOCKED_AT)
        .execute();
  }
}
