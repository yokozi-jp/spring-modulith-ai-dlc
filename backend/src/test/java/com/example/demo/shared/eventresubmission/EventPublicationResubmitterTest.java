package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.eventresubmission.EventPublicationResubmitOnce.Target;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.MutableClock;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.ProbeListener;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.ResubmissionProbe;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.CapturedLogRecords;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.javacrumbs.shedlock.core.LockProvider;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 定期の再投入（{@link EventPublicationResubmitter#resubmitOnce()}）と手での 1 回の再投入（{@link
 * EventPublicationResubmitOnce#run(EventPublicationResubmitOnce.Target)}）を、リスナーの呼び出しの回数、出版の状態と
 * {@code completion_attempts}、メトリクス、ログで検証する。
 *
 * <p>定期の実行は {@code .env.test} で無効なので、テストが直接呼ぶ。本番と同じくトランザクションの外から呼び、{@link Scenario} は完了を待つだけに使う。出版は
 * {@link Scenario} でテストのイベントを発行して作り、時刻は差し替えられる固定の時計で進める。
 *
 * <p>ロックの期限は DB の時刻で決まり、固定の時計では進まない。定期の再投入と手での 1 回の再投入を続けて呼ぶテストは、呼ぶ前にロックの期限を過去にする（{@link
 * #runJob()}、{@link #runOnce(Target)}）。DB の壁時計は NTP の同期で戻ることがあり、ShedLock の解放だけでは期限が残りうるためでもある。
 */
// 一つの文脈で出版の状態、メトリクス、ログ、ロック、方針を確かめるため、型とメソッドの数が多い。
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
@ResubmissionTestSupport.SharedContext
@EnableScenarios
class EventPublicationResubmitterTest {

  /** {@code event-publication.resubmission.wait-age}。 */
  private static final Duration WAIT_AGE = Duration.ofMinutes(5);

  /** テストのイベントの {@code event_type}。 */
  private static final String EVENT_TYPE = ResubmissionProbe.class.getName();

  /** FAILED の状態。 */
  private static final String FAILED = "FAILED";

  /** 回数の上限を超えて諦めた状態。 */
  private static final String ABANDONED = "ABANDONED";

  /** リスナーの呼び出しの回数の説明。 */
  private static final String CALLS = "id=%s のリスナーの呼び出し";

  /** archive の行の数の説明。 */
  private static final String ARCHIVED = "id=%s の archive の行";

  /** {@code completion_attempts} の説明。 */
  private static final String ATTEMPTS = "id=%s の attempts";

  /** Gauge の state の値。上限に達した出版。 */
  private static final String STATE_EXHAUSTED = "exhausted";

  /** Gauge の state の値。自動の再投入を待つ出版。 */
  private static final String STATE_RETRYING = "retrying";

  /** Gauge の state の値。失敗を記録せずに上限を超えて FAILED に残った出版。 */
  private static final String STATE_OVER_LIMIT = "over-limit";

  /** 再投入の結果の Counter の名前。 */
  private static final String OUTCOMES = "event.publication.resubmission.outcomes";

  /** 完了の待ちの説明。 */
  private static final String COMPLETED = "完了した出版";

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

  /** トランザクションの中で手での再投入を呼ぶ。 */
  @Autowired private PlatformTransactionManager transactionManager;

  /** メトリクス。 */
  @Autowired private MeterRegistry meterRegistry;

  /** OTLP へ送る LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

  /** 定期のタスクを持つ bean。@EnableScheduling がなければ空である。 */
  @Autowired private ObjectProvider<ScheduledTaskHolder> scheduledTaskHolders;

  @BeforeEach
  void reset() {
    clock.set(ResubmissionProbes.T0);
    listener.reset();
    ResubmissionTestSupport.expireLock(dsl);
  }

  /**
   * 変異などでテストが先に終わっても、リスナーを門に残さず、後始末（{@code CleanGeneratedTablesExtension}）が消した後に行を書かせないため。 JUnit は
   * {@code @AfterEach} を拡張の後始末より先に呼ぶ。
   */
  @AfterEach
  void openGate() {
    listener.open();
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
    assertThat(policies.nameOf(listenerId)).isEqualTo(ResubmissionProbes.PROBE_POLICY);
    assertThat(policies.nameOf("unknown.Listener.on(java.lang.Object)")).isEqualTo("default");
  }

  @Test
  @DisplayName("最後の試行からちょうど待ち時間では再投入せず、1 マイクロ秒過ぎると再投入して完了する")
  void resubmitsOnlyAfterWaitAge(final Scenario scenario) {
    final String id = failedOnce(scenario);

    clock.set(ResubmissionProbes.T0.plus(WAIT_AGE));
    runJob();

    assertThat(state(id).attempts()).as("id=%s のちょうど待ち時間の attempts", id).isEqualTo(1);
    assertThat(state(id).status()).as("id=%s のちょうど待ち時間の状態", id).isEqualTo(FAILED);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(1);

    clock.set(ResubmissionProbes.T0.plus(WAIT_AGE).plus(1, ChronoUnit.MICROS));
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
    final Instant firstResubmission = ResubmissionProbes.T0.plus(WAIT_AGE).plusSeconds(1);
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
  @DisplayName(
      "回数の上限の回数目の再投入で失敗した出版は ABANDONED で archive に移り、定期と手での再投入の対象にならず、Gauge の exhausted と ERROR に 1 回だけ出る")
  void exhaustedPublicationIsAbandonedAndNotResubmitted(final Scenario scenario) {
    listener.failTimes(Integer.MAX_VALUE);
    final double failedBefore = outcome("failed");
    final double exhaustedBefore = exhaustedCount();
    final String id = failedOnce(scenario);
    final String publicationId = publicationId(id);
    final Instant first = ResubmissionProbes.T0.plus(WAIT_AGE).plusSeconds(1);
    resubmitAndAwaitFailure(scenario, id, first, 2);
    assertThat(exhaustedErrors(publicationId)).as("id=%s の上限の前の ERROR", id).isEmpty();
    clock.set(first.plus(WAIT_AGE.multipliedBy(2)).plusSeconds(1));
    runJob();
    awaitState(
        scenario.stimulate(() -> {}),
        id,
        "ABANDONED の出版",
        s -> s.incomplete() == 0 && ABANDONED.equals(s.status()));

    clock.set(clock.instant().plus(Duration.ofDays(1)));
    runJob();

    assertThat(state(id).attempts()).as("id=%s の上限の後の attempts", id).isEqualTo(3);
    assertThat(runOnce(new Target.All()).resubmitted()).as("手での 1 回の再投入").isZero();
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(3);
    assertThat(gauge(STATE_EXHAUSTED)).as("上限に達した出版の数").isEqualTo(1.0);
    assertThat(gauge(STATE_RETRYING)).as("自動の再投入を待つ出版の数").isZero();
    assertThat(outcome("failed") - failedBefore).as("再投入の後の再失敗").isEqualTo(2.0);
    assertThat(exhaustedCount() - exhaustedBefore).as("上限に達した遷移").isEqualTo(1.0);
    final List<LogRecordData> errors = exhaustedErrors(publicationId);
    assertThat(errors).as("id=%s の上限に達した ERROR", id).hasSize(1);
    final LogRecordData error = errors.getFirst();
    assertThat(error.getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(error.getAttributes().get(AttributeKey.stringKey("event_publication.event_type")))
        .isEqualTo(EVENT_TYPE);
    assertThat(
            error
                .getAttributes()
                .get(AttributeKey.longKey("event_publication.completion_attempts")))
        .isEqualTo(3L);
  }

  @Test
  @DisplayName(
      "失敗を記録せずに上限を超えて FAILED に戻った出版（最後の試行の途中で落ちた出版）は、待ち時間を過ぎても再投入せず、Gauge の over-limit に出て retrying に出ない")
  void failedPublicationPastLimitWithoutRecordedFailureIsNotResubmitted() {
    // テストの上限は 2 回。2 回目の再投入の途中で落ち、古い出版として FAILED に戻した行にあたる。
    final UUID publication =
        ResubmissionTestSupport.insertPublication(
            dsl,
            UUID.randomUUID(),
            "over-limit-test",
            EVENT_TYPE,
            ResubmissionProbes.T0,
            FAILED,
            3);
    clock.set(ResubmissionProbes.T0.plus(Duration.ofDays(1)));

    runJob();

    assertThat(
            dsl.select(EVENT_PUBLICATION.STATUS, EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
                .from(EVENT_PUBLICATION)
                .where(EVENT_PUBLICATION.ID.eq(publication))
                .fetchOptional(Records.mapping(Row::new)))
        .as("上限を超えた FAILED の出版の未完了の行")
        .contains(new Row(FAILED, 3));
    assertThat(gauge(STATE_OVER_LIMIT)).as("上限を超えた FAILED の出版の数").isEqualTo(1.0);
    assertThat(gauge(STATE_RETRYING)).as("自動の再投入を待つ出版の数").isZero();
  }

  @Test
  @DisplayName("再投入した出版が処理中の間は数えず、リスナーが終わった後の次の回に completed を 1 だけ数える")
  void completionIsCountedOnTheRunAfterTheListenerFinishes(final Scenario scenario) {
    final String id = failedOnce(scenario);
    final CountDownLatch gate = listener.close();
    final double completedBefore = outcome("completed");
    final double resubmissionsBefore =
        meterRegistry.get("event.publication.resubmissions").counter().count();
    clock.set(ResubmissionProbes.T0.plus(WAIT_AGE).plusSeconds(1));

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
  @DisplayName("手での 1 回の再投入は、トランザクションの中で呼ぶと IllegalStateException で失敗し、何も再投入しない")
  void resubmitOnceFailsInsideTransaction(final Scenario scenario) {
    final String id = failedOnce(scenario);

    assertThatThrownBy(() ->
            new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> resubmitOnce.run(new Target.All())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transaction");
    assertThat(state(id).attempts()).as(ATTEMPTS, id).isEqualTo(1);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(1);
  }

  @Test
  @DisplayName("enabled が false なら、アプリ全体の文脈で定期のタスクを 1 つも登録せず、古い出版を FAILED に戻す処理も再投入も動かない")
  void disabledRegistersNoScheduledTaskInTheApplicationContext() {
    // 依存のライブラリ（Spring Modulith Moments など）が @EnableScheduling を足すと、enabled=false でもジョブが動く。
    // 古い出版を FAILED に戻す処理は、定期のジョブのロックの中だけで動く（EventPublicationResubmitterStalenessTest）。
    assertThat(scheduledTaskHolders.stream().flatMap(holder -> holder.getScheduledTasks().stream()))
        .as("登録された定期のタスク")
        .isEmpty();
  }

  @Test
  @DisplayName("完了して archive に移った出版は、時刻を進めても再投入しない")
  void archivedPublicationIsNotResubmitted(final Scenario scenario) {
    final String id = UUID.randomUUID().toString();
    awaitState(
        scenario.publish(new ResubmissionProbe(id, AsTenant.TENANT)),
        id,
        COMPLETED,
        s -> s.archived() == 1);

    clock.set(ResubmissionProbes.T0.plus(Duration.ofDays(1)));
    runJob();

    final State state = state(id);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(1);
    assertThat(state.archived()).as(ARCHIVED, id).isEqualTo(1);
    assertThat(state.incomplete()).as("id=%s の未完了の行", id).isZero();
    assertThat(state.attempts()).as(ATTEMPTS, id).isEqualTo(1);
  }

  @Test
  @DisplayName("2 つのスレッドから同時に動かしても、出版は 1 回だけ再投入される")
  void concurrentRunsResubmitOnce(final Scenario scenario) {
    final String id = failedOnce(scenario);
    clock.set(ResubmissionProbes.T0.plus(WAIT_AGE).plusSeconds(1));
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
    clock.set(ResubmissionProbes.T0.plus(WAIT_AGE).plusSeconds(1));
    final double skippedBefore = skippedRuns();

    ResubmissionTestSupport.whileLockHeld(dsl, lockProvider, clock, resubmitter::resubmitOnce);

    assertThat(state(id).attempts()).as(ATTEMPTS, id).isEqualTo(1);
    assertThat(listener.calls()).as(CALLS, id).isEqualTo(1);
    assertThat(skippedRuns() - skippedBefore).as("skipped の回の増分").isEqualTo(1.0);
  }

  @Test
  @DisplayName("手での 1 回の再投入は、待ち時間の前でも対象の出版を再投入し、別のスレッドのリスナーが終わるまで戻らない")
  void resubmitOnceWaitsForListenerOnAnotherThread(final Scenario scenario) throws Exception {
    final String id = failedOnce(scenario);
    final CountDownLatch gate = listener.close();
    final CompletableFuture<EventPublicationResubmitOnce.Outcome> future =
        ResubmissionTestSupport.onVirtualThread(
            () -> resubmitOnce.run(new Target.Policy(ResubmissionProbes.PROBE_POLICY)));
    ResubmissionTestSupport.awaitListenerAtGate(listener, "id=" + id);
    assertThat(future).as("門で待つ間の手での再投入").isNotDone();
    gate.countDown();

    final EventPublicationResubmitOnce.Outcome result =
        future.get(ResubmissionProbes.JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    // 戻った時点でリスナーが終わっていなければ、JVM を終えるとリスナーの処理が途中で切れる。
    assertThat(listener.finished()).as("戻った時点で終わったリスナーの呼び出し").isEqualTo(2);
    assertThat(result).isEqualTo(new EventPublicationResubmitOnce.Outcome(1, 1, 0, 0));
    assertThat(result.succeeded()).isTrue();
    assertThat(state(id).archived()).as(ARCHIVED, id).isEqualTo(1);
  }

  @Test
  @DisplayName("手での 1 回の再投入は、イベントの型と all でも選び、他の方針の名前では選ばない")
  void resubmitOnceSelectsByTarget(final Scenario scenario) {
    final String id = failedOnce(scenario);

    assertThat(runOnce(new Target.Policy("payment")).resubmitted()).as("payment の方針").isZero();
    assertThat(runOnce(new Target.EventType("java.lang.String")).resubmitted()).as("他の型").isZero();
    assertThat(runOnce(new Target.EventType(EVENT_TYPE)).resubmitted()).as("同じ型").isEqualTo(1);
    awaitState(scenario.stimulate(() -> {}), id, COMPLETED, s -> s.archived() == 1);

    listener.failTimes(1);
    final String other = failedOnce(scenario);
    assertThat(runOnce(new Target.All()).completed()).as("all").isEqualTo(1);
    assertThat(state(other).archived()).as(ARCHIVED, other).isEqualTo(1);
    assertThatThrownBy(() -> resubmitOnce.run(new Target.Policy("missing")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("手での 1 回の再投入は、ロックが空くまで待ち、lock-at-most-for の間に空かなければ失敗する")
  void resubmitOnceWaitsForLockAndGivesUp(final Scenario scenario) throws Exception {
    final String id = failedOnce(scenario);

    ResubmissionTestSupport.whileLockHeld(
        dsl,
        lockProvider,
        clock,
        () ->
            assertThatThrownBy(() -> resubmitOnce.run(new Target.All()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FailedPublicationSweep.LOCK_NAME));
    assertThat(state(id).attempts()).as("id=%s の失敗の後の attempts", id).isEqualTo(1);

    ResubmissionTestSupport.holdLock(lockProvider, clock);
    final CompletableFuture<EventPublicationResubmitOnce.Outcome> future =
        ResubmissionTestSupport.onVirtualThread(() -> resubmitOnce.run(new Target.All()));
    Awaitility.await("ロックを持つ間の手での再投入")
        .during(Duration.ofSeconds(1))
        .atMost(Duration.ofSeconds(3))
        .until(() -> !future.isDone());
    ResubmissionTestSupport.expireLock(dsl);
    assertThat(future.get(ResubmissionProbes.JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS).completed())
        .as("ロックが空いた後")
        .isEqualTo(1);
  }

  /** ロックの期限を過去にしてから、定期の再投入を呼ぶ。期限は DB の時刻で決まり、固定の時計では進まないため。 */
  private void runJob() {
    ResubmissionTestSupport.runJob(dsl, resubmitter);
  }

  /**
   * ロックの期限を過去にしてから、手での 1 回の再投入を呼ぶ。前の呼び出しの解放は、DB の壁時計が戻ると期限が残るため（{@link
   * ResubmissionTestSupport#holdLock}）。
   */
  private EventPublicationResubmitOnce.Outcome runOnce(final Target target) {
    ResubmissionTestSupport.expireLock(dsl);
    return resubmitOnce.run(target);
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

  /**
   * リスナーを 1 回失敗させ、今の時計の時刻に FAILED の出版を作り、時計を 1 マイクロ秒進めて、イベントの ID を返す。
   *
   * <p>進めないと再投入の対象にならない（ResubmissionOptionsLearningTest の
   * defaultsResubmitOnlyPublicationsOlderThanNow）。
   */
  private String failedOnce(final Scenario scenario) {
    if (listener.failuresLeft() == 0) {
      listener.failTimes(1);
    }
    final String id = UUID.randomUUID().toString();
    awaitState(
        scenario.publish(new ResubmissionProbe(id, AsTenant.TENANT)),
        id,
        "FAILED の出版",
        s -> FAILED.equals(s.status()));
    assertThat(state(id).attempts()).as("id=%s の最初の attempts", id).isEqualTo(1);
    clock.set(clock.instant().plus(1, ChronoUnit.MICROS));
    return id;
  }

  // 2 つのスレッドの同時の開始と完了を待つ。開始の合図を待って呼ぶ。
  @SuppressWarnings("PMD.DoNotUseThreads")
  private void resubmitFromTwoThreads() {
    final CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      final Runnable runAfterStart =
          () -> {
            try {
              if (!start.await(ResubmissionProbes.JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
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
        run.get(ResubmissionProbes.JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while resubmitting", exception);
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException exception) {
      throw new IllegalStateException("concurrent resubmission did not finish", exception);
    }
  }

  private double gauge(final String state) {
    return meterRegistry.get("event.publication.failed").tag("state", state).gauge().value();
  }

  private double outcome(final String outcome) {
    return meterRegistry.get(OUTCOMES).tag("outcome", outcome).counter().count();
  }

  private double exhaustedCount() {
    return meterRegistry.get("event.publication.resubmission.exhausted").counter().count();
  }

  private double skippedRuns() {
    return ResubmissionTestSupport.skippedRuns(meterRegistry);
  }

  private List<LogRecordData> exhaustedErrors(final String publicationId) {
    return ResubmissionTestSupport.exhaustedErrors(capturedLogRecords, publicationId);
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
}
