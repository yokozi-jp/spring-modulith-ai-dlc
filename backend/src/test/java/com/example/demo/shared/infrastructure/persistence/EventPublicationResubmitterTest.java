package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.jooq.modulith.Tables;
import com.example.demo.shared.events.ResubmissionPolicy;
import com.example.demo.testkit.CapturedLogRecords;
import com.example.demo.testkit.CommittedDatabaseTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.jooq.DSLContext;
import org.jooq.Records;
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
 * 定期の再投入（{@link EventPublicationResubmitter#resubmitOnce()}）と手での 1 回の再投入（{@link
 * EventPublicationResubmitOnce#run(String)}）を、リスナーの呼び出しの回数、出版の状態と {@code
 * completion_attempts}、メトリクス、ログで検証する。
 *
 * <p>定期の実行は {@code .env.test} で無効なので、テストが直接呼ぶ。本番と同じくトランザクションの外から呼び、{@link Scenario} は完了を待つだけに使う。出版は
 * {@link Scenario} でテストのイベントを発行して作り、時刻は差し替えられる固定の時計で進める。
 *
 * <p>ロックの期限は DB の時刻で決まり、固定の時計では進まない。定期の再投入を続けて呼ぶテストは、呼ぶ前にロックの期限を過去にする（{@link #runJob()}）。
 */
// 一つの文脈で出版の状態、メトリクス、ログ、ロック、方針を確かめるため、型とメソッドの数が多い。
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
@CommittedDatabaseTest
@EnableScenarios
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
class EventPublicationResubmitterTest {

  /** 最初の出版の時刻。マイクロ秒の精度にする。 */
  private static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** {@code event-publication.resubmission.wait-age}。 */
  private static final Duration WAIT_AGE = Duration.ofMinutes(5);

  /** テストのイベントの {@code event_type}。 */
  private static final String EVENT_TYPE = ResubmissionProbe.class.getName();

  /** 上限に達した出版の ERROR の本文。 */
  private static final String EXHAUSTED = "Event publication resubmission exhausted";

  /** FAILED の状態。 */
  private static final String FAILED = "FAILED";

  /** リスナーの呼び出しの回数の説明。 */
  private static final String CALLS = "id=%s のリスナーの呼び出し";

  /** {@code completion_attempts} の説明。 */
  private static final String ATTEMPTS = "id=%s の attempts";

  /** すべての FAILED の出版を対象にする手での 1 回の再投入の指定。 */
  private static final String ALL = "all";

  /** 再投入の結果の Counter の名前。 */
  private static final String OUTCOMES = "event.publication.resubmission.outcomes";

  /** 完了の待ちの説明。 */
  private static final String COMPLETED = "完了した出版";

  /** 2 つのスレッドの完了を待つ上限の秒数。 */
  private static final long JOIN_TIMEOUT_SECONDS = 30;

  /** 検証対象の定期のジョブ。 */
  @Autowired private EventPublicationResubmitter resubmitter;

  /** 検証対象の手での 1 回の再投入。 */
  @Autowired private EventPublicationResubmitOnce resubmitOnce;

  /** リスナーの方針。 */
  @Autowired private ResubmissionPolicies policies;

  /** 差し替えられる時計。 */
  @Autowired private MutableClock clock;

  /** 失敗の回数を決められるテストのリスナー。 */
  @Autowired private ProbeListener listener;

  /** 出版の状態を読む。 */
  @Autowired private DSLContext dsl;

  /** ロックを別に持つ。 */
  @Autowired private LockProvider lockProvider;

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
    expireLock();
  }

  @Test
  @DisplayName("実際の出版の listener_id は、起動のときに作ったリスナーの ID と一致し、@ResubmissionPolicy の方針に対応づく")
  void listenerIdOfRealPublicationMapsToAnnotatedPolicy(final Scenario scenario)
      throws NoSuchMethodException {
    final String id = failedOnce(scenario);

    final String listenerId =
        dsl.select(EVENT_PUBLICATION.LISTENER_ID)
            .from(EVENT_PUBLICATION)
            .where(eventIs(id))
            .fetchSingle(EVENT_PUBLICATION.LISTENER_ID);

    assertThat(listenerId)
        .isEqualTo(
            ResubmissionPolicies.listenerId(
                ProbeListener.class.getDeclaredMethod("on", ResubmissionProbe.class)));
    assertThat(policies.nameOf(listenerId)).isEqualTo("probe");
    assertThat(policies.nameOf("unknown.Listener.on(java.lang.Object)")).isEqualTo("default");
  }

  @Test
  @DisplayName("最後の試行からちょうど待ち時間では再投入せず、1 マイクロ秒過ぎると再投入して完了する")
  void resubmitsOnlyAfterWaitAge(final Scenario scenario) {
    final String id = failedOnce(scenario);

    clock.set(T0.plus(WAIT_AGE));
    runJob();

    assertThat(state(id).attempts()).as("id=%s のちょうど待ち時間の attempts", id).isEqualTo(1);
    assertThat(state(id).status()).as("id=%s のちょうど待ち時間の状態", id).isEqualTo(FAILED);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(1);

    clock.set(T0.plus(WAIT_AGE).plus(1, ChronoUnit.MICROS));
    runJob();
    awaitState(scenario.stimulate(() -> {}), id, COMPLETED, s -> s.archived() == 1);

    final State completed = state(id);
    assertThat(completed.status()).as("id=%s の状態", id).isEqualTo("COMPLETED");
    assertThat(completed.attempts()).as(ATTEMPTS, id).isEqualTo(2);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(2);
  }

  @Test
  @DisplayName("2 回目の再投入は、最後の再投入から wait-age × multiplier を過ぎるまで待つ")
  void secondResubmissionWaitsExponentially(final Scenario scenario) {
    listener.failTimes(2);
    final String id = failedOnce(scenario);
    final Instant firstResubmission = T0.plus(WAIT_AGE).plusSeconds(1);
    resubmitAndAwaitFailure(scenario, id, firstResubmission, 2);

    clock.set(firstResubmission.plus(WAIT_AGE.multipliedBy(2)));
    runJob();
    assertThat(state(id).attempts()).as("id=%s のちょうど 2 倍の待ち時間の attempts", id).isEqualTo(2);

    clock.set(firstResubmission.plus(WAIT_AGE.multipliedBy(2)).plus(1, ChronoUnit.MICROS));
    runJob();
    awaitState(scenario.stimulate(() -> {}), id, COMPLETED, s -> s.archived() == 1);
    assertThat(state(id).attempts()).as(ATTEMPTS, id).isEqualTo(3);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(3);
  }

  @Test
  @DisplayName("方針の上限に達した出版は再投入せず、Gauge の exhausted を 1 にし、ERROR を同じインスタンスで 1 回だけ記録する")
  void exhaustedPublicationIsReportedOnceAndNotResubmitted(final Scenario scenario) {
    listener.failTimes(Integer.MAX_VALUE);
    final double failedBefore = outcome("failed");
    final double exhaustedBefore = exhaustedCount();
    final String id = failedOnce(scenario);
    final Instant first = T0.plus(WAIT_AGE).plusSeconds(1);
    resubmitAndAwaitFailure(scenario, id, first, 2);
    final Instant second = first.plus(WAIT_AGE.multipliedBy(2)).plusSeconds(1);
    resubmitAndAwaitFailure(scenario, id, second, 3);

    clock.set(second.plus(Duration.ofDays(1)));
    runJob();

    final String publicationId = publicationId(id);
    assertThat(state(id).attempts()).as("id=%s の上限の後の attempts", id).isEqualTo(3);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(3);
    assertThat(
            meterRegistry.get("event.publication.failed").tag("state", "exhausted").gauge().value())
        .as("上限に達した出版の数")
        .isEqualTo(1.0);
    final List<LogRecordData> errors = exhaustedErrors(publicationId);
    assertThat(outcome("failed") - failedBefore).as("再投入の後の再失敗").isEqualTo(2.0);
    assertThat(exhaustedCount() - exhaustedBefore).as("上限に達した遷移").isEqualTo(1.0);
    assertThat(errors).as("id=%s の上限に達した ERROR", id).hasSize(1);
    final LogRecordData error = errors.getFirst();
    assertThat(error.getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(error.getAttributes().get(AttributeKey.stringKey("event_publication.event_type")))
        .isEqualTo(EVENT_TYPE);

    runJob();

    assertThat(exhaustedErrors(publicationId)).as("id=%s の 2 回目の後の ERROR", id).hasSize(1);
    assertThat(state(id).attempts()).as("id=%s の 2 回目の後の attempts", id).isEqualTo(3);
  }

  @Test
  @DisplayName("再投入した出版が処理中の間は数えず、リスナーが終わった後の次の回に completed を 1 だけ数える")
  void completionIsCountedOnTheRunAfterTheListenerFinishes(final Scenario scenario) {
    final String id = failedOnce(scenario);
    final CountDownLatch gate = listener.close();
    final double completedBefore = outcome("completed");
    final double resubmissionsBefore =
        meterRegistry.get("event.publication.resubmissions").counter().count();
    clock.set(T0.plus(WAIT_AGE).plusSeconds(1));

    runJob();
    runJob();

    assertThat(
            meterRegistry.get("event.publication.resubmissions").counter().count()
                - resubmissionsBefore)
        .as("再投入した出版の数")
        .isEqualTo(1.0);
    assertThat(outcome("completed") - completedBefore).as("処理中").isZero();

    gate.countDown();
    awaitState(scenario.stimulate(() -> {}), id, COMPLETED, s -> s.archived() == 1);
    runJob();
    runJob();

    assertThat(outcome("completed") - completedBefore).as("完了した後は 1 回だけ").isEqualTo(1.0);
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
    awaitState(scenario.publish(new ResubmissionProbe(id)), id, COMPLETED, s -> s.archived() == 1);

    clock.set(T0.plus(Duration.ofDays(1)));
    runJob();

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
    final double skippedBefore = skippedRuns();

    resubmitFromTwoThreads();
    awaitState(scenario.stimulate(() -> {}), id, COMPLETED, s -> s.archived() == 1);

    final State completed = state(id);
    assertThat(completed.attempts()).as(ATTEMPTS, id).isEqualTo(2);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(2);
    assertThat(skippedRuns() - skippedBefore).as("skipped の回の増分").isEqualTo(1.0);
  }

  @Test
  @DisplayName("別に ShedLock のロックを持つ間は再投入せず、skipped の回を 1 増やす")
  void heldLockSkipsRun(final Scenario scenario) {
    final String id = failedOnce(scenario);
    clock.set(T0.plus(WAIT_AGE).plusSeconds(1));
    final double skippedBefore = skippedRuns();

    final SimpleLock held = holdLock();
    try {
      resubmitter.resubmitOnce();
    } finally {
      held.unlock();
    }

    assertThat(state(id).attempts()).as(ATTEMPTS, id).isEqualTo(1);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(1);
    assertThat(skippedRuns() - skippedBefore).as("skipped の回の増分").isEqualTo(1.0);
  }

  @Test
  @DisplayName("手での 1 回の再投入は、待ち時間の前でも対象の出版を再投入し、別のスレッドのリスナーが終わるまで戻らない")
  void resubmitOnceWaitsForListenerOnAnotherThread(final Scenario scenario) {
    final String id = failedOnce(scenario);
    final CountDownLatch gate = listener.close();
    releaseLater(gate::countDown, Duration.ofSeconds(2));

    final EventPublicationResubmitOnce.Outcome result = resubmitOnce.run("policy:probe");

    // 戻った時点でリスナーが終わっていなければ、JVM を終えるとリスナーの処理が途中で切れる。
    assertThat(listener.finished()).as("戻った時点で終わったリスナーの呼び出し").isEqualTo(2);
    assertThat(result).isEqualTo(new EventPublicationResubmitOnce.Outcome(1, 1, 0, 0));
    assertThat(result.succeeded()).isTrue();
    assertThat(state(id).archived()).as("id=%s の archive の行", id).isEqualTo(1);
  }

  @Test
  @DisplayName("手での 1 回の再投入は、イベントの型と all でも選び、他の方針の名前では選ばない")
  void resubmitOnceSelectsByTarget(final Scenario scenario) {
    final String id = failedOnce(scenario);

    assertThat(resubmitOnce.run("policy:payment").resubmitted()).as("payment の方針").isZero();
    assertThat(resubmitOnce.run("event-type:java.lang.String").resubmitted()).as("他の型").isZero();
    assertThat(resubmitOnce.run("event-type:" + EVENT_TYPE).resubmitted()).as("同じ型").isEqualTo(1);
    awaitState(scenario.stimulate(() -> {}), id, COMPLETED, s -> s.archived() == 1);

    listener.failTimes(1);
    final String other = failedOnce(scenario);
    assertThat(resubmitOnce.run(ALL).completed()).as("all").isEqualTo(1);
    assertThat(state(other).archived()).as("id=%s の archive の行", other).isEqualTo(1);
    assertThatThrownBy(() -> resubmitOnce.run("policy:missing"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resubmitOnce.run("OrderConfirmed"))
        .isInstanceOf(IllegalArgumentException.class);
    // 空白以外で対象として読めない値は、false も含めて黙って何もしないのではなく失敗する。
    for (final String invalid : List.of("false", "", "event-type:")) {
      assertThatThrownBy(() -> resubmitOnce.run(invalid))
          .as("対象 '%s'", invalid)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("手での 1 回の再投入は、ロックが空くまで待ち、lock-at-most-for の間に空かなければ失敗する")
  void resubmitOnceWaitsForLockAndGivesUp(final Scenario scenario) {
    final String id = failedOnce(scenario);

    final SimpleLock held = holdLock();
    try {
      assertThatThrownBy(() -> resubmitOnce.run(ALL))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(EventPublicationResubmitter.LOCK_NAME);
    } finally {
      held.unlock();
    }
    assertThat(state(id).attempts()).as("id=%s の失敗の後の attempts", id).isEqualTo(1);

    final SimpleLock released = holdLock();
    releaseLater(released::unlock, Duration.ofSeconds(1));
    assertThat(resubmitOnce.run(ALL).completed()).as("ロックが空いた後").isEqualTo(1);
  }

  /** ロックの期限を過去にしてから、定期の再投入を呼ぶ。期限は DB の時刻で決まり、固定の時計では進まないため。 */
  private void runJob() {
    expireLock();
    resubmitter.resubmitOnce();
  }

  private void expireLock() {
    dsl.update(Tables.SHEDLOCK)
        .set(Tables.SHEDLOCK.LOCK_UNTIL, Tables.SHEDLOCK.LOCKED_AT)
        .execute();
  }

  /** 他のインスタンスの代わりに、同じロックを 1 分持つ。 */
  private SimpleLock holdLock() {
    final Optional<SimpleLock> lock =
        lockProvider.lock(
            new LockConfiguration(
                Instant.now(clock),
                EventPublicationResubmitter.LOCK_NAME,
                Duration.ofMinutes(1),
                Duration.ZERO));
    return lock.orElseThrow(
        () -> new AssertionError("テストのロック " + EventPublicationResubmitter.LOCK_NAME + " を取れない"));
  }

  /** 時刻を進めて定期の再投入を呼び、リスナーが失敗して attempts が増えた FAILED に戻るまで待つ。 */
  private void resubmitAndAwaitFailure(
      final Scenario scenario, final String id, final Instant at, final int attempts) {
    clock.set(at);
    runJob();
    awaitState(
        scenario.stimulate(() -> {}),
        id,
        "再投入の後の FAILED",
        s -> FAILED.equals(s.status()) && Integer.valueOf(attempts).equals(s.attempts()));
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
        s -> FAILED.equals(s.status()));
    assertThat(state(id).attempts()).as("id=%s の最初の attempts", id).isEqualTo(1);
    return id;
  }

  // 他のスレッドの処理の途中を作るため、別のスレッドで決めた時間だけ待ってから放す。
  @SuppressWarnings("PMD.DoNotUseThreads")
  private static void releaseLater(final Runnable release, final Duration after) {
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                Thread.sleep(after);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
              }
              release.run();
            });
  }

  // 2 つのスレッドの同時の開始と完了を待つ。開始の合図を待って呼ぶ。
  @SuppressWarnings("PMD.DoNotUseThreads")
  private void resubmitFromTwoThreads() {
    final CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      final Runnable runAfterStart =
          () -> {
            try {
              if (!start.await(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start signal was not given");
              }
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("interrupted before resubmitting", exception);
            }
            resubmitter.resubmitOnce();
          };
      final List<Future<?>> runs =
          List.of(executor.submit(runAfterStart), executor.submit(runAfterStart));
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

  private double outcome(final String outcome) {
    return meterRegistry.get(OUTCOMES).tag("outcome", outcome).counter().count();
  }

  private double exhaustedCount() {
    return meterRegistry.get("event.publication.resubmission.exhausted").counter().count();
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

  private static org.jooq.Condition eventIs(final String id) {
    return EVENT_PUBLICATION
        .EVENT_TYPE
        .eq(EVENT_TYPE)
        .and(EVENT_PUBLICATION.SERIALIZED_EVENT.contains(id));
  }

  private String publicationId(final String id) {
    final UUID publicationId =
        dsl.select(EVENT_PUBLICATION.ID)
            .from(EVENT_PUBLICATION)
            .where(eventIs(id))
            .fetchSingle(EVENT_PUBLICATION.ID);
    return Objects.requireNonNull(publicationId, "event_publication.id").toString();
  }

  /** イベントの ID の出版の、未完了の行と archive の行を読む。 */
  private State state(final String id) {
    final List<Row> incomplete =
        dsl.select(EVENT_PUBLICATION.STATUS, EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
            .from(EVENT_PUBLICATION)
            .where(eventIs(id))
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

  /** 呼び出しの回数を数え、決めた回数だけ失敗し、門が閉じていれば開くまで待つリスナー。 */
  // @ApplicationModuleListener のトランザクションの proxy を作るため final にしない。受信メソッドは on と命名する規約のため。
  @SuppressWarnings("PMD.ShortMethodName")
  /* package */ static class ProbeListener {

    /** 門が開くのを待つ上限の秒数。 */
    private static final long GATE_TIMEOUT_SECONDS = 30;

    /** 呼び出しの回数。 */
    private final AtomicInteger callCount = new AtomicInteger();

    /** 最後まで終えた呼び出しの回数。 */
    private final AtomicInteger finishedCount = new AtomicInteger();

    /** 残りの失敗の回数。 */
    private final AtomicInteger remainingFailures = new AtomicInteger();

    /** 閉じた門。開くまで呼び出しを待たせる。 */
    private final AtomicReference<@Nullable CountDownLatch> gate = new AtomicReference<>();

    // 門で待つ間に割り込まれたら、割り込みの状態を戻して失敗させる。
    @SuppressWarnings("PMD.DoNotUseThreads")
    @ApplicationModuleListener
    @ResubmissionPolicy("probe")
    /* package */ void on(final ResubmissionProbe event) {
      callCount.incrementAndGet();
      try {
        final @Nullable CountDownLatch latch = gate.get();
        if (latch != null && !latch.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          throw new IllegalStateException("gate was not opened: id=" + event.id());
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted: id=" + event.id(), exception);
      }
      try {
        if (remainingFailures.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
          throw new IllegalStateException("probe listener is configured to fail: id=" + event.id());
        }
      } finally {
        finishedCount.incrementAndGet();
      }
    }

    /* package */ void reset() {
      callCount.set(0);
      finishedCount.set(0);
      remainingFailures.set(0);
      gate.set(null);
    }

    /* package */ void failTimes(final int times) {
      remainingFailures.set(times);
    }

    /* package */ int failuresLeft() {
      return remainingFailures.get();
    }

    /** 門を閉じ、開けるための latch を返す。 */
    /* package */ CountDownLatch close() {
      final CountDownLatch latch = new CountDownLatch(1);
      gate.set(latch);
      return latch;
    }

    /* package */ int calls() {
      return callCount.get();
    }

    /* package */ int finished() {
      return finishedCount.get();
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
