package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.events.ListenerIds;
import com.example.demo.testkit.CapturedLogRecords;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link FailedPublicationGauges} が、本物の DB の行から {@code state} ごとの件数と最古の経過秒数を出し、上限を超えた {@code
 * FAILED} の出版の ERROR を件数が 0 から増えた回だけ出すことを検証する。
 *
 * <p>テストの回数の上限（{@code abandon-after-attempts}）は 2 で、{@code probe-once} の方針のリスナーだけ 1（自動で再投入しない）である。
 */
@ResubmissionTestSupport.SharedContext
class FailedPublicationGaugesTest {

  /** 出版の時刻の基準。マイクロ秒の精度にする。 */
  private static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** Gauge の state の値。上限の内の FAILED の出版。 */
  private static final String RETRYING = "retrying";

  /** Gauge の state の値。上限を超えた FAILED の出版。 */
  private static final String OVER_LIMIT = "over-limit";

  /** Gauge の state の値。archive の ABANDONED の出版。 */
  private static final String EXHAUSTED = "exhausted";

  /** Gauge の state の値のすべて。 */
  private static final List<String> STATES = List.of(RETRYING, OVER_LIMIT, EXHAUSTED);

  /** archive の表で諦めた状態。 */
  private static final String ABANDONED = "ABANDONED";

  /** 上限を超えた FAILED の出版が現れたときの ERROR の本文。 */
  private static final String EXCEEDED = "Failed event publications exceed the resubmission limit";

  /** 上限を超えた FAILED の出版の件数を持つ LogRecord の属性。 */
  private static final AttributeKey<Long> OVER_LIMIT_COUNT =
      AttributeKey.longKey("event_publication.over_limit.count");

  /** 出版の行を読み書きする。 */
  @Autowired private DSLContext dsl;

  /** リスナーごとの再投入の回数の上限。 */
  @Autowired private ResubmissionPolicies policies;

  /** OTLP へ送る LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

  /** この文脈の OpenTelemetry。 */
  @Autowired private OpenTelemetry openTelemetry;

  /** Gauge を読むための、このテストだけのレジストリ。 */
  private SimpleMeterRegistry meterRegistry;

  /** 検証対象。 */
  private FailedPublicationGauges gauges;

  @BeforeEach
  void setUp() {
    // 別のコンテキストが appender の送り先を差し替えていても、このコンテキストの記録を読む。
    OpenTelemetryAppender.install(openTelemetry);
    meterRegistry = new SimpleMeterRegistry();
    gauges = new FailedPublicationGauges(dsl, meterRegistry, policies);
  }

  @Test
  @DisplayName(
      "上限の内の FAILED を retrying に、上限を超えた FAILED を over-limit に、archive の ABANDONED を exhausted に、件数と最古の経過秒数で出し、COMPLETED は数えない")
  void splitsFailedByLimitAndCountsAbandonedAsExhaustedWithOldestAge() {
    insertFailed(1, T0.plusSeconds(5));
    insertFailed(3, T0);
    insertFailed(2, T0.plusSeconds(10));
    insertFailed(3, T0.plusSeconds(15));
    ResubmissionTestSupport.insertArchived(dsl, T0, "COMPLETED");
    ResubmissionTestSupport.insertArchived(dsl, T0.plusSeconds(20), ABANDONED);
    ResubmissionTestSupport.insertArchived(dsl, T0.plusSeconds(30), ABANDONED);

    gauges.refresh(T0.plusSeconds(100));

    assertThat(count(RETRYING)).isEqualTo(2.0);
    assertThat(count(OVER_LIMIT)).isEqualTo(2.0);
    assertThat(count(EXHAUSTED)).isEqualTo(2.0);
    assertThat(oldestAge(RETRYING)).as("最古の retrying は T0 + 5 秒").isEqualTo(95.0);
    assertThat(oldestAge(OVER_LIMIT)).as("最古の over-limit は T0").isEqualTo(100.0);
    assertThat(oldestAge(EXHAUSTED)).as("最古の exhausted は T0 + 20 秒").isEqualTo(80.0);
  }

  @Test
  @DisplayName("FAILED と ABANDONED の出版がないとき 0 で、時計が出版より前のとき経過秒数を負にせず 0 にする")
  void zeroWithoutPublicationsAndWhenClockIsBehind() {
    gauges.refresh(T0);

    for (final String state : STATES) {
      assertThat(count(state)).as("%s の件数", state).isZero();
      assertThat(oldestAge(state)).as("%s の経過秒数", state).isZero();
    }

    insertFailed(1, T0.plusSeconds(60));
    insertFailed(3, T0.plusSeconds(60));
    ResubmissionTestSupport.insertArchived(dsl, T0.plusSeconds(60), ABANDONED);
    gauges.refresh(T0);

    for (final String state : STATES) {
      assertThat(count(state)).as("%s の件数", state).isEqualTo(1.0);
      assertThat(oldestAge(state)).as("時計が出版より前の %s の経過秒数", state).isZero();
    }
  }

  @ParameterizedTest(name = "listener={0}, completion_attempts={1}")
  @CsvSource({
    "gauge-test,",
    "gauge-test,0",
    "gauge-test,1",
    "gauge-test,2",
    "gauge-test,3",
    "gauge-test,4",
    "once,0",
    "once,1",
    "once,2"
  })
  @DisplayName("FAILED の行を over-limit に数えるかは、行のリスナーの、定期の再投入の回数の上限（ResubmissionPolicies）と一致する")
  void overLimitPredicateAgreesWithJavaRule(final String listener, final @Nullable Integer attempts)
      throws NoSuchMethodException {
    final String listenerId =
        "once".equals(listener)
            ? ListenerIds.fromMethod(
                    ResubmissionProbes.OnceProbeListener.class.getDeclaredMethod(
                        "on", ResubmissionProbes.OnceProbe.class))
                .orElseThrow(() -> new AssertionError("no listener id: OnceProbeListener.on"))
            : listener;
    final UUID id =
        ResubmissionTestSupport.insertPublication(
            dsl, UUID.randomUUID(), listenerId, T0, "FAILED", 0);
    dsl.update(EVENT_PUBLICATION)
        .set(EVENT_PUBLICATION.COMPLETION_ATTEMPTS, attempts)
        .where(EVENT_PUBLICATION.ID.eq(id))
        .execute();
    // Spring Modulith と EventPublicationStates は、空の completion_attempts を 0 と読む。
    final boolean exceeded =
        (attempts == null ? 0 : attempts) > policies.limitOf(listenerId).maxResubmissions();

    gauges.refresh(T0);

    assertThat(count(OVER_LIMIT)).as(OVER_LIMIT).isEqualTo(exceeded ? 1.0 : 0.0);
    assertThat(count(RETRYING)).as(RETRYING).isEqualTo(exceeded ? 0.0 : 1.0);
  }

  @Test
  @DisplayName("上限を超えた FAILED の出版の ERROR は、件数が 0 から増えた回だけ出し、残る間と増えた回では出さず、0 に戻った後にまた現れた回に出す")
  void overLimitErrorIsLoggedOnlyWhenCountRisesFromZero() {
    final int errorsBefore = capturedLogRecords.withBody(EXCEEDED).size();
    final UUID first = insertFailed(3, T0);
    final UUID second = insertFailed(3, T0);

    gauges.refresh(T0);
    gauges.refresh(T0);
    final UUID third = insertFailed(3, T0);
    gauges.refresh(T0);

    final List<LogRecordData> errors = capturedLogRecords.withBody(EXCEEDED);
    assertThat(errors).as("残る間と増えた回の ERROR").hasSize(errorsBefore + 1);
    assertThat(errors.getLast().getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(errors.getLast().getAttributes().get(OVER_LIMIT_COUNT)).isEqualTo(2L);
    assertThat(count(OVER_LIMIT)).isEqualTo(3.0);

    dsl.deleteFrom(EVENT_PUBLICATION)
        .where(EVENT_PUBLICATION.ID.in(first, second, third))
        .execute();
    gauges.refresh(T0);
    assertThat(count(OVER_LIMIT)).as("片付けた後").isZero();
    insertFailed(3, T0);
    gauges.refresh(T0);

    final List<LogRecordData> again = capturedLogRecords.withBody(EXCEEDED);
    assertThat(again).as("0 に戻った後にまた現れた回の ERROR").hasSize(errorsBefore + 2);
    assertThat(again.getLast().getAttributes().get(OVER_LIMIT_COUNT)).isEqualTo(1L);
  }

  /** リスナーを持たない FAILED の出版の行を作る。 */
  private UUID insertFailed(final int completionAttempts, final Instant publicationDate) {
    return ResubmissionTestSupport.insertPublication(
        dsl, UUID.randomUUID(), "gauge-test", publicationDate, "FAILED", completionAttempts);
  }

  private double count(final String state) {
    return meterRegistry.get("event.publication.failed").tag("state", state).gauge().value();
  }

  private double oldestAge(final String state) {
    return meterRegistry
        .get("event.publication.failed.oldest.age")
        .tag("state", state)
        .timeGauge()
        .value(TimeUnit.SECONDS);
  }
}
