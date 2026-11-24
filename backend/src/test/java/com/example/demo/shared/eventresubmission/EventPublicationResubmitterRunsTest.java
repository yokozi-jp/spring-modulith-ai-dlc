package com.example.demo.shared.eventresubmission;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.testkit.CapturedLogRecords;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.support.LockException;
import org.jooq.DSLContext;
import org.jooq.ExecuteListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.FailedEventPublications;

/**
 * {@link EventPublicationResubmitter#resubmitOnce()} のジョブの実行の回数 {@code
 * event.publication.resubmission.runs} が、正常、他のインスタンスのロック、既知の失敗、予期しない例外、Gauge の集計の失敗のそれぞれで、正しい
 * outcome の tag を 1 だけ増やすことを検証する。
 *
 * <p>ジョブは、本物の DB とロックの表、モックの {@link FailedEventPublications}、テストごとの {@link SimpleMeterRegistry}
 * で作る。 Spring の文脈は {@link EventPublicationResubmitterTest} と同じ設定で共有する。
 */
@ResubmissionTestSupport.SharedContext
class EventPublicationResubmitterRunsTest {

  /** 完了した回の outcome。 */
  private static final String COMPLETED = "completed";

  /** 失敗した回の outcome。 */
  private static final String FAILED = "failed";

  /** 省いた回の outcome。 */
  private static final String SKIPPED = "skipped";

  /** 予期しない例外の ERROR の本文。 */
  private static final String CRASHED = "Event publication resubmission crashed";

  /** 既知の失敗の WARN の本文。 */
  private static final String FAILED_MESSAGE = "Event publication resubmission failed";

  /** Gauge の集計の失敗の WARN の本文。 */
  private static final String GAUGES_FAILED = "Event publication gauges refresh failed";

  /** 例外の型を持つ LogRecord の属性。 */
  private static final AttributeKey<String> EXCEPTION_TYPE =
      AttributeKey.stringKey("exception.type");

  /** 実際のロックの実行。 */
  @Autowired private LockingTaskExecutor lockingTaskExecutor;

  /** ロックを別に持つ。 */
  @Autowired private LockProvider lockProvider;

  /** リスナーの方針。 */
  @Autowired private ResubmissionPolicies policies;

  /** 設定値。 */
  @Autowired private EventPublicationResubmissionConfig.Properties properties;

  /** 再投入の回数の上限。 */
  @Autowired private EventPublicationResubmissionConfig.AbandonProperties abandon;

  /** 差し替えられる時計。 */
  @Autowired private EventPublicationResubmitterTest.MutableClock clock;

  /** 集計の Gauge が読む DB。 */
  @Autowired private DSLContext dsl;

  /** OTLP へ送る LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

  /** この文脈の OpenTelemetry。 */
  @Autowired private OpenTelemetry openTelemetry;

  /** 回の数を読むための、このテストだけのレジストリ。 */
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void reset() {
    // 別のコンテキストが appender の送り先を差し替えていても、このコンテキストの記録を読む。
    OpenTelemetryAppender.install(openTelemetry);
    meterRegistry = new SimpleMeterRegistry();
    clock.set(Instant.parse("2026-10-10T01:02:03.123456Z"));
    expireLock();
  }

  @Test
  @DisplayName("ロックを取って最後まで実行すると completed を 1 だけ増やす")
  void completedRun() {
    newResubmitter(lockingTaskExecutor).resubmitOnce();

    assertThat(runs(COMPLETED)).isEqualTo(1.0);
    assertThat(runs(FAILED)).isZero();
    assertThat(runs(SKIPPED)).isZero();
  }

  @Test
  @DisplayName("他のインスタンスがロックを持つ間は skipped を 1 だけ増やす")
  void skippedRun() {
    // 他のインスタンスの代わりに、同じロックを 1 分持つ。
    final SimpleLock held =
        lockProvider
            .lock(
                new LockConfiguration(
                    clock.instant(),
                    FailedPublicationSweep.LOCK_NAME,
                    Duration.ofMinutes(1),
                    Duration.ZERO))
            .orElseThrow(() -> new AssertionError("テストのロックを取れない"));
    try {
      newResubmitter(lockingTaskExecutor).resubmitOnce();
    } finally {
      held.unlock();
    }

    assertThat(runs(SKIPPED)).isEqualTo(1.0);
    assertThat(runs(COMPLETED)).isZero();
    assertThat(runs(FAILED)).isZero();
  }

  @Test
  @DisplayName("ロックの失敗は WARN で記録して failed を 1 だけ増やし、例外は外へ出さない")
  void lockExceptionIsCountedAsFailedWithWarning() {
    final int warningsBefore = capturedLogRecords.withBody(FAILED_MESSAGE).size();
    final int crashesBefore = capturedLogRecords.withBody(CRASHED).size();

    newResubmitter(throwing(new LockException("lock failed"))).resubmitOnce();

    assertThat(runs(FAILED)).isEqualTo(1.0);
    assertThat(runs(COMPLETED)).isZero();
    final List<LogRecordData> warnings = capturedLogRecords.withBody(FAILED_MESSAGE);
    assertThat(warnings).as("既知の失敗の WARN").hasSize(warningsBefore + 1);
    assertThat(warnings.getLast().getSeverity()).isEqualTo(Severity.WARN);
    assertThat(capturedLogRecords.withBody(CRASHED))
        .as("既知の失敗は crashed にしない")
        .hasSize(crashesBefore);
  }

  @Test
  @DisplayName("予期しない実行時の例外は ERROR の crashed で記録して failed を 1 だけ増やし、例外は外へ出さない")
  void unexpectedExceptionIsCountedAsFailedWithError() {
    final int crashesBefore = capturedLogRecords.withBody(CRASHED).size();

    newResubmitter(throwing(new IllegalStateException("unexpected"))).resubmitOnce();

    assertThat(runs(FAILED)).isEqualTo(1.0);
    assertThat(runs(COMPLETED)).isZero();
    assertThat(runs(SKIPPED)).isZero();
    final List<LogRecordData> crashes = capturedLogRecords.withBody(CRASHED);
    assertThat(crashes).as("予期しない例外の ERROR").hasSize(crashesBefore + 1);
    assertThat(crashes.getLast().getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(crashes.getLast().getAttributes().get(EXCEPTION_TYPE))
        .isEqualTo(IllegalStateException.class.getName());
  }

  @Test
  @DisplayName("Gauge の集計が DB の失敗で終わっても、WARN で記録して Gauge を前の値に残し、再投入を続けて completed を数える")
  void gaugeRefreshFailureDoesNotStopResubmission() {
    final AtomicBoolean failArchiveReads = new AtomicBoolean();
    final DSLContext failingDsl =
        dsl.configuration()
            .derive(
                ExecuteListener.onExecuteStart(context -> {
                  final String sql = context.sql();
                  if (failArchiveReads.get()
                      && sql != null
                      && sql.contains("event_publication_archive")) {
                    throw new org.jooq.exception.DataAccessException("archive is unavailable");
                  }
                }))
            .dsl();
    final FailedEventPublications failed = Mockito.mock(FailedEventPublications.class);
    final EventPublicationResubmitter resubmitter =
        new EventPublicationResubmitter(
            failingDsl,
            failed,
            lockingTaskExecutor,
            policies,
            clock,
            meterRegistry,
            properties,
            abandon);
    final Supplier<Double> exhaustedGauge =
        () ->
            meterRegistry.get("event.publication.failed").tag("state", "exhausted").gauge().value();
    ResubmissionTestSupport.insertArchived(dsl, clock.instant(), "ABANDONED");
    resubmitter.resubmitOnce();
    final double exhaustedBefore = exhaustedGauge.get();
    final int warningsBefore = capturedLogRecords.withBody(GAUGES_FAILED).size();

    failArchiveReads.set(true);
    ResubmissionTestSupport.insertArchived(dsl, clock.instant(), "ABANDONED");
    expireLock();
    resubmitter.resubmitOnce();

    assertThat(exhaustedGauge.get()).as("集計が失敗した回の Gauge").isEqualTo(exhaustedBefore);
    assertThat(capturedLogRecords.withBody(GAUGES_FAILED)).hasSize(warningsBefore + 1);
    assertThat(runs(COMPLETED)).isEqualTo(2.0);
    assertThat(runs(FAILED)).isZero();
    Mockito.verify(failed, Mockito.times(2)).resubmit(Mockito.any());
  }

  /** テスト専用のレジストリを使うジョブを作る。FailedEventPublications は何もしないモックで足りる。 */
  private EventPublicationResubmitter newResubmitter(final LockingTaskExecutor executor) {
    return new EventPublicationResubmitter(
        dsl,
        Mockito.mock(FailedEventPublications.class),
        executor,
        policies,
        clock,
        meterRegistry,
        properties,
        abandon);
  }

  /** ロックの実行が、決めた例外を投げるモック。 */
  private static LockingTaskExecutor throwing(final RuntimeException exception) {
    final LockingTaskExecutor executor = Mockito.mock(LockingTaskExecutor.class);
    Mockito.doThrow(exception)
        .when(executor)
        .executeWithLock(Mockito.any(Runnable.class), Mockito.any(LockConfiguration.class));
    return executor;
  }

  private double runs(final String outcome) {
    return meterRegistry
        .get("event.publication.resubmission.runs")
        .tag("outcome", outcome)
        .counter()
        .count();
  }

  private void expireLock() {
    ResubmissionTestSupport.expireLock(dsl);
  }
}
