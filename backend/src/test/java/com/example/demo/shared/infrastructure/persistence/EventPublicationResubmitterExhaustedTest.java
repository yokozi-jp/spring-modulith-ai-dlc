package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.testkit.CapturedLogRecords;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.FailedEventPublications;

/**
 * 上限に達した出版の ERROR の記録（{@code reportExhausted}）を、DB の行を直接作って検証する。
 *
 * <p>ジョブは、本物の DB とロック、モックの {@link FailedEventPublications}、テストごとの {@link SimpleMeterRegistry} で作る。
 * ジョブごとに記録済みの ID が空から始まるので、テスト同士が影響しない。Spring の文脈は {@link EventPublicationResubmitterRunsTest}
 * と同じ設定で共有する。
 */
// 出版の行の準備、ジョブの設定の差し替え、ERROR と INFO の読み出しを同じ文脈で扱うため、メソッドの数が多い。
@SuppressWarnings("PMD.TooManyMethods")
@ResubmissionTestSupport.SharedContext
class EventPublicationResubmitterExhaustedTest {

  /** 最初の出版の時刻。マイクロ秒の精度にする。 */
  private static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** 再投入と記録の INFO の本文。 */
  private static final String RESUBMITTED = "Event publications resubmitted";

  /** 方針を持たない listener_id で、default（max-resubmissions=1）が使われる。 */
  private static final String LISTENER_ID = "exhausted-test";

  /** default の max-resubmissions（1）を超える completion_attempts。 */
  private static final int EXHAUSTED_ATTEMPTS = 2;

  /** 回収の周期（1 時間）以上の間隔。ロックを取った実行のたびに回収する。 */
  private static final Duration SWEEP_EVERY_RUN = Duration.ofHours(1);

  /** 実際のロックの実行。 */
  @Autowired private LockingTaskExecutor lockingTaskExecutor;

  /** リスナーの方針。 */
  @Autowired private ResubmissionPolicies policies;

  /** 設定値。 */
  @Autowired private EventPublicationResubmissionConfig.Properties properties;

  /** 差し替えられる時計。 */
  @Autowired private EventPublicationResubmitterTest.MutableClock clock;

  /** 出版の行を作る DB。 */
  @Autowired private DSLContext dsl;

  /** OTLP へ送る LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

  @BeforeEach
  void reset() {
    clock.set(T0);
    expireLock();
  }

  @Test
  @DisplayName("上限に達した出版が残っても、2 回目の実行は新しく記録せず INFO も出さない")
  void secondRunReportsNothingAndLogsNoInfo() {
    final UUID id = insertExhausted(0);
    final EventPublicationResubmitter resubmitter =
        newResubmitter(withIntervalAndMaxPerRun(SWEEP_EVERY_RUN, properties.maxPerRun()));

    final int infoBefore = capturedLogRecords.withBody(RESUBMITTED).size();
    resubmitter.resubmitOnce();

    assertThat(exhaustedErrors(id)).as("1 回目の ERROR").hasSize(1);
    assertThat(capturedLogRecords.withBody(RESUBMITTED))
        .as("1 回目は新しく記録したので INFO")
        .hasSize(infoBefore + 1);
    assertThat(exhaustedCount(capturedLogRecords.withBody(RESUBMITTED).getLast())).isEqualTo(1);

    expireLock();
    resubmitter.resubmitOnce();

    assertThat(exhaustedErrors(id)).as("2 回目の ERROR").hasSize(1);
    assertThat(capturedLogRecords.withBody(RESUBMITTED))
        .as("2 回目は再投入も新しい記録もないので INFO を出さない")
        .hasSize(infoBefore + 1);
  }

  @Test
  @DisplayName("行を消して同じ ID で作り直すと、記録から外れていて再び ERROR にする")
  void recreatedPublicationIsReportedAgain() {
    final UUID id = insertExhausted(0);
    final EventPublicationResubmitter resubmitter =
        newResubmitter(withIntervalAndMaxPerRun(SWEEP_EVERY_RUN, properties.maxPerRun()));
    resubmitter.resubmitOnce();
    assertThat(exhaustedErrors(id)).hasSize(1);

    dsl.deleteFrom(EVENT_PUBLICATION).where(EVENT_PUBLICATION.ID.eq(id)).execute();
    expireLock();
    resubmitter.resubmitOnce();
    assertThat(exhaustedErrors(id)).as("行が無い間は記録しない").hasSize(1);

    insertExhausted(id, 0);
    expireLock();
    resubmitter.resubmitOnce();

    assertThat(exhaustedErrors(id)).as("作り直した後は再び記録する").hasSize(2);
  }

  @Test
  @DisplayName("max-per-run を超える上限到達の出版は、1 回に max-per-run 件だけ ERROR にし、残りは古い順に次の回に記録する")
  void reportsAtMostMaxPerRunPerRunOldestFirst() {
    final int maxPerRun = 2;
    final List<UUID> ids =
        IntStream.range(0, maxPerRun + 1).mapToObj(this::insertExhausted).toList();
    final EventPublicationResubmitter resubmitter =
        newResubmitter(withIntervalAndMaxPerRun(SWEEP_EVERY_RUN, maxPerRun));

    resubmitter.resubmitOnce();

    assertThat(ids.subList(0, maxPerRun))
        .allSatisfy(id -> assertThat(exhaustedErrors(id)).hasSize(1));
    assertThat(exhaustedErrors(ids.getLast())).as("max-per-run を超えた分は次の回").isEmpty();

    expireLock();
    resubmitter.resubmitOnce();

    assertThat(ids).allSatisfy(id -> assertThat(exhaustedErrors(id)).hasSize(1));
  }

  @Test
  @DisplayName("新しく上限に達した出版の回収は、最初の回と ceil(1 時間 / interval) 回ごとだけ行う")
  void sweepRunsOnTheFirstRunAndThenPeriodically() {
    // interval が 10 分なら、1 時間は 6 回ごと。
    final EventPublicationResubmitter resubmitter =
        newResubmitter(withIntervalAndMaxPerRun(Duration.ofMinutes(10), properties.maxPerRun()));
    final UUID first = insertExhausted(0);

    resubmitter.resubmitOnce();
    final UUID later = insertExhausted(1);
    for (int run = 2; run <= 6; run++) {
      expireLock();
      resubmitter.resubmitOnce();
      assertThat(exhaustedErrors(later)).as("%s 回目は回収しない", run).isEmpty();
    }
    expireLock();
    resubmitter.resubmitOnce();

    assertThat(exhaustedErrors(first)).as("最初の回で回収した").hasSize(1);
    assertThat(exhaustedErrors(later)).as("7 回目で回収した").hasSize(1);
  }

  /** 上限に達した FAILED の出版の行を作る。publication_date は offsetSeconds だけ T0 から進める。 */
  private UUID insertExhausted(final int offsetSeconds) {
    return insertExhausted(UUID.randomUUID(), offsetSeconds);
  }

  private UUID insertExhausted(final UUID id, final int offsetSeconds) {
    return ResubmissionTestSupport.insertPublication(
        dsl, id, LISTENER_ID, T0.plusSeconds(offsetSeconds), "FAILED", EXHAUSTED_ATTEMPTS);
  }

  private EventPublicationResubmissionConfig.Properties withIntervalAndMaxPerRun(
      final Duration interval, final int maxPerRun) {
    return new EventPublicationResubmissionConfig.Properties(
        properties.waitAge(),
        properties.inFlightStaleness(),
        interval,
        maxPerRun,
        // lock-at-least-for（interval の半分）が lock-at-most-for 以下でないと、ロックの設定が不正になる。
        interval.multipliedBy(2),
        properties.policies());
  }

  private EventPublicationResubmitter newResubmitter(
      final EventPublicationResubmissionConfig.Properties jobProperties) {
    return new EventPublicationResubmitter(
        dsl,
        Mockito.mock(FailedEventPublications.class),
        lockingTaskExecutor,
        policies,
        clock,
        new SimpleMeterRegistry(),
        jobProperties);
  }

  private List<LogRecordData> exhaustedErrors(final UUID id) {
    return ResubmissionTestSupport.exhaustedErrors(capturedLogRecords, id.toString());
  }

  private static long exhaustedCount(final LogRecordData record) {
    return record.getAttributes().get(AttributeKey.longKey("event_publication.exhausted.count"));
  }

  private void expireLock() {
    ResubmissionTestSupport.expireLock(dsl);
  }
}
