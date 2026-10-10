package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.testkit.CapturedLogRecords;
import com.example.demo.testkit.CommittedDatabaseTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link EventPublicationResubmitter#resubmitOnce()} を唯一の入口にし、待ち時間の境界、回数の上限と ERROR、archive
 * の出版、同時の実行、 ロックの保持を、リスナーの呼び出しの回数、出版の状態と {@code completion_attempts}、メトリクス、ログで検証する。
 *
 * <p>定期の実行は {@code .env.test} で無効なので、テストが直接呼ぶ。本番と同じくトランザクションの外から呼び、{@link Scenario} は完了を待つだけに使う。出版は
 * {@link Scenario} でテストのイベントを発行して作り、 時刻は差し替えられる固定の時計で進める。
 */
// 一つの文脈で出版の状態、メトリクス、ログ、ロックを確かめるため、型とメソッドの数が多い。
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
@CommittedDatabaseTest
@EnableScenarios
@Import(EventPublicationResubmitterTest.ProbeConfiguration.class)
@TestPropertySource(
    properties = {
      "event-publication.resubmission.wait-age=PT5M",
      "event-publication.resubmission.max-resubmissions=1",
      "event-publication.resubmission.max-per-run=100"
    })
class EventPublicationResubmitterTest {

  /** 最初の出版の時刻。マイクロ秒の精度にする。 */
  private static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** {@code event-publication.resubmission.wait-age}。 */
  private static final Duration WAIT_AGE = Duration.ofMinutes(5);

  /** テストのイベントの {@code event_type}。 */
  private static final String EVENT_TYPE = ResubmissionProbe.class.getName();

  /** 上限に達した出版の ERROR の本文。 */
  private static final String EXHAUSTED = "Event publication resubmission exhausted";

  /** リスナーの呼び出しの回数の説明。 */
  private static final String CALLS = "id=%s のリスナーの呼び出し";

  /** {@code completion_attempts} の説明。 */
  private static final String ATTEMPTS = "id=%s の attempts";

  /** 2 つのスレッドの完了を待つ上限の秒数。 */
  private static final long JOIN_TIMEOUT_SECONDS = 30;

  /** 検証対象のジョブ。 */
  @Autowired private EventPublicationResubmitter resubmitter;

  /** 差し替えられる時計。 */
  @Autowired private MutableClock clock;

  /** 失敗の回数を決められるテストのリスナー。 */
  @Autowired private ProbeListener listener;

  /** 出版の状態を読む。 */
  @Autowired private DSLContext dsl;

  /** ロックを別の接続で持つ。 */
  @Autowired private DataSource dataSource;

  /** メトリクス。 */
  @Autowired private MeterRegistry meterRegistry;

  /** OTLP へ送る LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

  /** 定期のタスクを持つ bean。@EnableScheduling がなければ空である。 */
  @Autowired private ObjectProvider<ScheduledTaskHolder> scheduledTaskHolders;

  @BeforeEach
  void reset() {
    clock.set(T0);
    listener.reset();
  }

  @Test
  @DisplayName("最後の試行からちょうど待ち時間では再投入せず、1 マイクロ秒過ぎると再投入して完了する")
  void resubmitsOnlyAfterWaitAge(final Scenario scenario) {
    final String id = failedOnce(scenario);

    clock.set(T0.plus(WAIT_AGE));
    resubmitter.resubmitOnce();

    assertThat(state(id).attempts()).as("id=%s のちょうど待ち時間の attempts", id).isEqualTo(1);
    assertThat(state(id).status()).as("id=%s のちょうど待ち時間の状態", id).isEqualTo("FAILED");
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(1);

    clock.set(T0.plus(WAIT_AGE).plus(1, ChronoUnit.MICROS));
    resubmitter.resubmitOnce();
    awaitState(scenario.stimulate(() -> {}), id, "完了した出版", s -> s.archived() == 1);

    final State completed = state(id);
    assertThat(completed.status()).as("id=%s の状態", id).isEqualTo("COMPLETED");
    assertThat(completed.attempts()).as(ATTEMPTS, id).isEqualTo(2);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(2);
  }

  @Test
  @DisplayName("上限に達した出版は再投入せず、Gauge の exhausted を 1 にし、ERROR を同じインスタンスで 1 回だけ記録する")
  void exhaustedPublicationIsReportedOnceAndNotResubmitted(final Scenario scenario) {
    listener.failTimes(Integer.MAX_VALUE);
    final String id = failedOnce(scenario);

    clock.set(T0.plus(WAIT_AGE).plusSeconds(1));
    resubmitter.resubmitOnce();
    awaitState(
        scenario.stimulate(() -> {}),
        id,
        "再投入の後の FAILED",
        s -> "FAILED".equals(s.status()) && Integer.valueOf(2).equals(s.attempts()));

    clock.set(T0.plus(WAIT_AGE.multipliedBy(3)));
    resubmitter.resubmitOnce();

    final String publicationId = publicationId(id);
    assertThat(state(id).attempts()).as("id=%s の上限の後の attempts", id).isEqualTo(2);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(2);
    assertThat(
            meterRegistry.get("event.publication.failed").tag("state", "exhausted").gauge().value())
        .as("上限に達した出版の数")
        .isEqualTo(1.0);
    final List<LogRecordData> errors = exhaustedErrors(publicationId);
    assertThat(errors).as("id=%s の上限に達した ERROR", id).hasSize(1);
    final LogRecordData error = errors.getFirst();
    assertThat(error.getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(error.getAttributes().get(AttributeKey.stringKey("event_publication.event_type")))
        .isEqualTo(EVENT_TYPE);

    resubmitter.resubmitOnce();

    assertThat(exhaustedErrors(publicationId)).as("id=%s の 2 回目の後の ERROR", id).hasSize(1);
    assertThat(state(id).attempts()).as("id=%s の 2 回目の後の attempts", id).isEqualTo(2);
  }

  @Test
  @DisplayName("enabled が false なら、アプリ全体の文脈で定期のタスクを 1 つも登録しない")
  void disabledRegistersNoScheduledTaskInTheApplicationContext() {
    // 依存のライブラリ（Spring Modulith Moments など）が @EnableScheduling を足すと、enabled=false でもジョブが動く。
    assertThat(scheduledTaskHolders.stream().flatMap(holder -> holder.getScheduledTasks().stream()))
        .as("登録された定期のタスク")
        .isEmpty();
  }

  @Test
  @DisplayName("完了して archive に移った出版は、時刻を進めても再投入しない")
  void archivedPublicationIsNotResubmitted(final Scenario scenario) {
    final String id = UUID.randomUUID().toString();
    awaitState(scenario.publish(new ResubmissionProbe(id)), id, "完了した出版", s -> s.archived() == 1);

    clock.set(T0.plus(Duration.ofDays(1)));
    resubmitter.resubmitOnce();

    final State state = state(id);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(1);
    assertThat(state.archived()).as("id=%s の archive の行", id).isEqualTo(1);
    assertThat(state.incomplete()).as("id=%s の未完了の行", id).isZero();
    assertThat(state.attempts()).as(ATTEMPTS, id).isEqualTo(1);
  }

  @Test
  @DisplayName("2 つのスレッドから同時に動かしても、出版は 1 回だけ再投入される")
  void concurrentRunsResubmitOnce(final Scenario scenario) {
    final String id = failedOnce(scenario);
    clock.set(T0.plus(WAIT_AGE).plusSeconds(1));

    resubmitFromTwoThreads();
    awaitState(scenario.stimulate(() -> {}), id, "完了した出版", s -> s.archived() == 1);

    final State completed = state(id);
    assertThat(completed.attempts()).as(ATTEMPTS, id).isEqualTo(2);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(2);
  }

  @Test
  @DisplayName("別の接続が advisory lock を持つ間は再投入せず、skipped の回を 1 増やす")
  void heldLockSkipsRun(final Scenario scenario) throws SQLException {
    final String id = failedOnce(scenario);
    clock.set(T0.plus(WAIT_AGE).plusSeconds(1));
    final double skippedBefore = skippedRuns();

    try (Connection connection = dataSource.getConnection()) {
      final DSLContext other = DSL.using(connection, SQLDialect.POSTGRES);
      assertThat(advisoryLock(other, "pg_try_advisory_lock")).as("テストの接続のロック").isTrue();
      try {
        resubmitter.resubmitOnce();
      } finally {
        assertThat(advisoryLock(other, "pg_advisory_unlock")).as("テストの接続の解放").isTrue();
      }
    }

    assertThat(state(id).attempts()).as(ATTEMPTS, id).isEqualTo(1);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(1);
    assertThat(skippedRuns() - skippedBefore).as("skipped の回の増分").isEqualTo(1.0);
  }

  /** リスナーを 1 回失敗させ、T0 に FAILED の出版を作り、イベントの ID を返す。 */
  private String failedOnce(final Scenario scenario) {
    if (listener.failuresLeft() == 0) {
      listener.failTimes(1);
    }
    final String id = UUID.randomUUID().toString();
    awaitState(
        scenario.publish(new ResubmissionProbe(id)),
        id,
        "FAILED の出版",
        s -> "FAILED".equals(s.status()));
    assertThat(state(id).attempts()).as("id=%s の最初の attempts", id).isEqualTo(1);
    return id;
  }

  // 2 つのスレッドの同時の開始と完了を待つ。
  @SuppressWarnings("PMD.DoNotUseThreads")
  private void resubmitFromTwoThreads() {
    final CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      final List<Future<?>> runs =
          List.of(executor.submit(() -> runAfter(start)), executor.submit(() -> runAfter(start)));
      start.countDown();
      for (final Future<?> run : runs) {
        run.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while resubmitting", exception);
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException exception) {
      throw new IllegalStateException("concurrent resubmission did not finish", exception);
    }
  }

  // 開始の合図を待って呼ぶ。
  @SuppressWarnings("PMD.DoNotUseThreads")
  private void runAfter(final CountDownLatch start) {
    try {
      if (!start.await(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException("start signal was not given");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted before resubmitting", exception);
    }
    resubmitter.resubmitOnce();
  }

  private static boolean advisoryLock(final DSLContext session, final String function) {
    return Boolean.TRUE.equals(
        session
            .select(
                DSL.function(
                    DSL.name(function),
                    Boolean.class,
                    DSL.val(EventPublicationResubmitter.LOCK_KEY)))
            .fetchOne(0, Boolean.class));
  }

  private double skippedRuns() {
    return meterRegistry
        .get("event.publication.resubmission.runs")
        .tag("outcome", "skipped")
        .counter()
        .count();
  }

  private List<LogRecordData> exhaustedErrors(final String publicationId) {
    return capturedLogRecords.withBody(EXHAUSTED).stream()
        .filter(
            record ->
                publicationId.equals(
                    record.getAttributes().get(AttributeKey.stringKey("event_publication.id"))))
        .toList();
  }

  /** 状態が条件を満たすまで待つ。満たさなければイベントの ID と最後に読んだ状態を出して失敗する。 */
  private void awaitState(
      final Scenario.When<?> when,
      final String id,
      final String expectation,
      final Predicate<State> done) {
    final AtomicReference<@Nullable State> last = new AtomicReference<>();
    try {
      when.andWaitForStateChange(
              () -> {
                final State state = state(id);
                last.set(state);
                return done.test(state);
              },
              Boolean.TRUE::equals)
          .andVerify(reached -> {});
    } catch (org.awaitility.core.ConditionTimeoutException exception) {
      throw new AssertionError(
          "id=" + id + " の" + expectation + "を待ったが届かない: 最後の状態=" + last.get(), exception);
    }
  }

  private String publicationId(final String id) {
    final UUID publicationId =
        dsl.select(EVENT_PUBLICATION.ID)
            .from(EVENT_PUBLICATION)
            .where(
                EVENT_PUBLICATION
                    .EVENT_TYPE
                    .eq(EVENT_TYPE)
                    .and(EVENT_PUBLICATION.SERIALIZED_EVENT.contains(id)))
            .fetchSingle(EVENT_PUBLICATION.ID);
    return Objects.requireNonNull(publicationId, "event_publication.id").toString();
  }

  /** イベントの ID の出版の、未完了の行と archive の行を読む。 */
  private State state(final String id) {
    final List<Row> incomplete =
        dsl.select(EVENT_PUBLICATION.STATUS, EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
            .from(EVENT_PUBLICATION)
            .where(
                EVENT_PUBLICATION
                    .EVENT_TYPE
                    .eq(EVENT_TYPE)
                    .and(EVENT_PUBLICATION.SERIALIZED_EVENT.contains(id)))
            .fetch(Records.mapping(Row::new));
    final List<Row> archived =
        dsl.select(EVENT_PUBLICATION_ARCHIVE.STATUS, EVENT_PUBLICATION_ARCHIVE.COMPLETION_ATTEMPTS)
            .from(EVENT_PUBLICATION_ARCHIVE)
            .where(
                EVENT_PUBLICATION_ARCHIVE
                    .EVENT_TYPE
                    .eq(EVENT_TYPE)
                    .and(EVENT_PUBLICATION_ARCHIVE.SERIALIZED_EVENT.contains(id)))
            .fetch(Records.mapping(Row::new));
    final Row row =
        incomplete.isEmpty()
            ? archived.isEmpty() ? new Row(null, null) : archived.getLast()
            : incomplete.getFirst();
    return new State(incomplete.size(), archived.size(), row.status(), row.attempts());
  }

  /** イベント出版レジストリの 1 行の状態と回数。 */
  /* package */ record Row(@Nullable String status, @Nullable Integer attempts) {}

  /**
   * イベントの ID の出版の状態。
   *
   * @param incomplete 未完了の行の数
   * @param archived archive の行の数
   * @param status 未完了の行があればその状態、なければ archive の最後の行の状態
   * @param attempts 同じ行の {@code completion_attempts}
   */
  /* package */ record State(
      int incomplete, int archived, @Nullable String status, @Nullable Integer attempts) {}

  /** テストのイベント。 */
  /* package */ record ResubmissionProbe(String id) {}

  /** 呼び出しの回数を数え、決めた回数だけ失敗するリスナー。 */
  // @ApplicationModuleListener のトランザクションの proxy を作るため final にしない。受信メソッドは on と命名する規約のため。
  @SuppressWarnings("PMD.ShortMethodName")
  /* package */ static class ProbeListener {

    /** 呼び出しの回数。 */
    private final AtomicInteger callCount = new AtomicInteger();

    /** 残りの失敗の回数。 */
    private final AtomicInteger remainingFailures = new AtomicInteger();

    @ApplicationModuleListener
    /* package */ void on(final ResubmissionProbe event) {
      callCount.incrementAndGet();
      if (remainingFailures.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
        throw new IllegalStateException("probe listener is configured to fail: id=" + event.id());
      }
    }

    /* package */ void reset() {
      callCount.set(0);
      remainingFailures.set(0);
    }

    /* package */ void failTimes(final int times) {
      remainingFailures.set(times);
    }

    /* package */ int failuresLeft() {
      return remainingFailures.get();
    }

    /* package */ int calls() {
      return callCount.get();
    }
  }

  /** テストが時刻を進められる時計。値は常に {@link Clock#fixed} の値にする。 */
  /* package */ static final class MutableClock extends Clock {

    /** 今の固定の時計。 */
    private final AtomicReference<Clock> delegate =
        new AtomicReference<>(fixed(T0, ZoneOffset.UTC));

    /* package */ void set(final Instant instant) {
      delegate.set(fixed(instant, ZoneOffset.UTC));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return delegate.get().withZone(zone);
    }

    @Override
    public Instant instant() {
      return delegate.get().instant();
    }
  }

  /** 時計とテストのリスナーを登録する。リスナーは他のテストの component scan に入らないよう {@code @Bean} にする。 */
  @TestConfiguration(proxyBeanMethods = false)
  /* package */ static class ProbeConfiguration {

    @Bean
    @Primary
    /* package */ MutableClock mutableClock() {
      return new MutableClock();
    }

    @Bean
    /* package */ ProbeListener probeListener() {
      return new ProbeListener();
    }
  }
}
