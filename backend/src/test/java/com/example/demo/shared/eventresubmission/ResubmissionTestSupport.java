package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.eventresubmission.ResubmissionProbes.ProbeConfiguration;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.ProbeListener;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.ResubmissionProbe;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.CapturedLogRecords;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.CommittedDatabaseTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.TestPropertySource;

/** 再投入のテストで使う、Spring の文脈の設定、設定値、ジョブの組み立て、ロックの期限、出版の行と archive の行、条件の関数に渡す出版、上限到達の ERROR の操作。 */
// 再投入のテストが共有する組み立て、ロック、行の操作を一か所に集めるため、メソッドと型の数が多い。
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
final class ResubmissionTestSupport {

  /** 他のインスタンスの代わりにロックを持つ時間。lock-at-most-for（3 秒）の待ちと、ロックを持つ間の 3 秒の待ちより長くする。 */
  private static final Duration HELD_LOCK = Duration.ofSeconds(10);

  private ResubmissionTestSupport() {}

  /** 再投入のテストが同じ設定で Spring のテストの文脈を共有するための合成アノテーション。 */
  /* package */ @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @CommittedDatabaseTest
  @Import(ProbeConfiguration.class)
  @TestPropertySource(
      properties = {
        "event-publication.resubmission.wait-age=PT5M",
        "event-publication.resubmission.in-flight-staleness=PT15M",
        "event-publication.resubmission.interval=PT2S",
        "event-publication.resubmission.lock-at-most-for=PT3S",
        "event-publication.resubmission.max-per-run=100",
        "spring.modulith.events.resubmission.abandon-after-attempts=2",
        "event-publication.resubmission.policies.probe.multiplier=2",
        "event-publication.resubmission.policies.probe.max-wait=PT20M",
        "event-publication.resubmission.policies.probe-once.multiplier=2",
        "event-publication.resubmission.policies.probe-once.max-wait=PT20M",
        "event-publication.resubmission.policies.probe-once.abandon-after-attempts=1",
        // リスナーが進めない変異で、手での再投入が application.yaml の PT10M まで待たないようにする。
        "event-publication.resubmit-once.completion-timeout=PT15S"
      })
  @interface SharedContext {}

  /** 処理を仮想スレッドで呼び、その結果を返す。 */
  // テストのスレッドが待たずに、別のスレッドの処理の途中を操作するため。
  @SuppressWarnings("PMD.DoNotUseThreads")
  /* package */ static <T> CompletableFuture<T> onVirtualThread(final Supplier<T> task) {
    return CompletableFuture.supplyAsync(task, runnable -> Thread.ofVirtual().start(runnable));
  }

  /**
   * 古い出版を FAILED に戻す処理を何もしないモックにする。時計を進めたテストが、行の状態を変えないためである。
   *
   * <p>本物の処理を試すテストは、{@code resubmitter} に本物の {@link StalePublications} を渡す。
   */
  /* package */ static StalePublications noStaleCheck() {
    return Mockito.mock(StalePublications.class);
  }

  /**
   * 方針を {@code policies} にし、ほかの値を固定した再投入の設定値を作る。archive を読み戻す期間は {@code max(3 × 10 分, 25 分 + 10 分 +
   * 2 × 1 分)} の 37 分である。
   */
  /* package */ static EventPublicationResubmissionConfig.Properties properties(
      final Map<String, EventPublicationResubmissionConfig.Policy> policies) {
    return new EventPublicationResubmissionConfig.Properties(
        Duration.ofMinutes(5),
        Duration.ofMinutes(25),
        Duration.ofMinutes(1),
        100,
        Duration.ofMinutes(10),
        policies);
  }

  /**
   * 同じ {@code dsl}、{@code clock}、{@code properties} から、古い出版を FAILED に戻す処理を {@code
   * stalePublications} にして、Sweep と定期のジョブを組み立てる。
   */
  /* package */ static EventPublicationResubmitter resubmitter(
      final DSLContext dsl,
      final FailedEventPublications failed,
      final LockingTaskExecutor lockingTaskExecutor,
      final Clock clock,
      final EventPublicationResubmissionConfig.Properties properties,
      final ResubmissionPolicies policies,
      final MeterRegistry meterRegistry,
      final StalePublications stalePublications) {
    return new EventPublicationResubmitter(
        dsl,
        new FailedPublicationSweep(
            dsl, failed, lockingTaskExecutor, clock, properties, stalePublications),
        policies,
        clock,
        meterRegistry,
        properties);
  }

  /** ロックの期限を過去にしてから、定期の再投入を呼ぶ。期限は DB の時刻で決まり、固定の時計では進まないため。 */
  /* package */ static void runJob(final DSLContext dsl, final EventPublicationResubmitter job) {
    expireLock(dsl);
    job.resubmitOnce();
  }

  /** 再投入したリスナーが門に着く（最初の配信と合わせて 2 回呼ばれる）まで待つ。 */
  /* package */ static void awaitListenerAtGate(final ProbeListener listener, final String label) {
    Awaitility.await(label + " の再投入したリスナーが門に着く")
        .atMost(Duration.ofSeconds(ResubmissionProbes.JOIN_TIMEOUT_SECONDS))
        .untilAsserted(() -> assertThat(listener.calls()).as(label).isEqualTo(2));
  }

  /** 他のインスタンスがロックを持っていて飛ばした回の数。 */
  /* package */ static double skippedRuns(final MeterRegistry meterRegistry) {
    return meterRegistry
        .get("event.publication.resubmission.runs")
        .tag("outcome", "skipped")
        .counter()
        .count();
  }

  /** ロックの期限を過去にする。 */
  /* package */ static void expireLock(final DSLContext dsl) {
    CleanGeneratedTablesExtension.expireLocks(dsl);
  }

  /**
   * 他のインスタンスの代わりに、定期の再投入と手での 1 回の再投入のロックを {@link #HELD_LOCK} だけ持つ。
   *
   * <p>放すときは {@link SimpleLock#unlock()} ではなく、ロックの期限を過去にする（{@link #expireLock}）。lock-at-least-for が
   * 0 の解放は期限を {@code max(locked_at, DB の今)} にするため、取った後に DB の壁時計が戻ると、{@code locked_at} まで放されないためである。
   */
  /* package */ static void holdLock(final LockProvider lockProvider, final Clock clock) {
    if (lockProvider
        .lock(
            new LockConfiguration(
                Instant.now(clock), FailedPublicationSweep.LOCK_NAME, HELD_LOCK, Duration.ZERO))
        .isEmpty()) {
      throw new AssertionError("テストのロック " + FailedPublicationSweep.LOCK_NAME + " を取れない");
    }
  }

  /** ロックの期限を過去にしてから、他のインスタンスの代わりにロックを持って {@code task} を呼び、終わったら期限を過去にする。 */
  /* package */ static void whileLockHeld(
      final DSLContext dsl,
      final LockProvider lockProvider,
      final Clock clock,
      final Runnable task) {
    expireLock(dsl);
    holdLock(lockProvider, clock);
    try {
      task.run();
    } finally {
      expireLock(dsl);
    }
  }

  /**
   * テストのリスナーを 1 回失敗させ、今の時計の時刻に FAILED の出版を作り、その ID を返す。
   *
   * <p>今の時刻の出版は再投入の対象にならないため（{@code ResubmissionOptionsLearningTest} の {@code
   * defaultsResubmitOnlyPublicationsOlderThanNow}）、再投入する前に時計を進める。
   */
  /* package */ static UUID failedProbe(
      final Scenario scenario, final ProbeListener listener, final DSLContext dsl) {
    listener.failTimes(1);
    final String id = UUID.randomUUID().toString();
    final AtomicReference<@Nullable UUID> publication = new AtomicReference<>();
    scenario
        .publish(new ResubmissionProbe(id, AsTenant.TENANT))
        .andWaitForStateChange(() ->
            dsl.select(EVENT_PUBLICATION.ID)
                .from(EVENT_PUBLICATION)
                .where(
                    EVENT_PUBLICATION
                        .SERIALIZED_EVENT
                        .contains(id)
                        .and(EVENT_PUBLICATION.STATUS.eq(EventPublication.Status.FAILED.name())))
                .fetchOptional(EVENT_PUBLICATION.ID)
                .orElse(null))
        .andVerify(publication::set);
    return Objects.requireNonNull(publication.get(), "FAILED の出版の ID");
  }

  /** {@code test.event} の出版の行を作り、その ID を返す。 */
  /* package */ static UUID insertPublication(
      final DSLContext dsl,
      final UUID id,
      final String listenerId,
      final Instant publicationDate,
      final String status,
      final int completionAttempts) {
    return insertPublication(
        dsl, id, listenerId, "test.event", publicationDate, status, completionAttempts);
  }

  /**
   * {@code eventType} の出版の行を、本文を {@code {"id":"<ID>"}} にして作り、その ID を返す。
   *
   * <p>Spring Modulith は、古い出版を FAILED に戻す処理と再投入で行のイベントを読み戻し、読めない型の行を落とす。
   * 読み戻させる行には、テストのイベントの型（{@link ResubmissionProbe}）を渡す。
   */
  /* package */ static UUID insertPublication(
      final DSLContext dsl,
      final UUID id,
      final String listenerId,
      final String eventType,
      final Instant publicationDate,
      final String status,
      final int completionAttempts) {
    dsl.insertInto(EVENT_PUBLICATION)
        .set(EVENT_PUBLICATION.ID, id)
        .set(EVENT_PUBLICATION.LISTENER_ID, listenerId)
        .set(EVENT_PUBLICATION.EVENT_TYPE, eventType)
        .set(EVENT_PUBLICATION.SERIALIZED_EVENT, "{\"id\":\"" + id + "\"}")
        .set(EVENT_PUBLICATION.PUBLICATION_DATE, publicationDate)
        .set(EVENT_PUBLICATION.STATUS, status)
        .set(EVENT_PUBLICATION.COMPLETION_ATTEMPTS, completionAttempts)
        .execute();
    return id;
  }

  /**
   * {@code test.event} の archive の行を、出版の時刻に終わった状態（{@code COMPLETED} か {@code ABANDONED}）で作り、その ID
   * を返す。
   */
  /* package */ static UUID insertArchived(
      final DSLContext dsl, final Instant publicationDate, final String status) {
    return insertArchived(dsl, publicationDate, status, UUID.randomUUID());
  }

  /** {@link #insertArchived(DSLContext, Instant, String)} の、ID を指定する版。 */
  /* package */ static UUID insertArchived(
      final DSLContext dsl, final Instant publicationDate, final String status, final UUID id) {
    dsl.insertInto(EVENT_PUBLICATION_ARCHIVE)
        .set(EVENT_PUBLICATION_ARCHIVE.ID, id)
        .set(EVENT_PUBLICATION_ARCHIVE.LISTENER_ID, "archived-test")
        .set(EVENT_PUBLICATION_ARCHIVE.EVENT_TYPE, "test.event")
        .set(EVENT_PUBLICATION_ARCHIVE.SERIALIZED_EVENT, "{}")
        .set(EVENT_PUBLICATION_ARCHIVE.PUBLICATION_DATE, publicationDate)
        .set(EVENT_PUBLICATION_ARCHIVE.COMPLETION_DATE, publicationDate)
        .set(EVENT_PUBLICATION_ARCHIVE.STATUS, status)
        .set(EVENT_PUBLICATION_ARCHIVE.COMPLETION_ATTEMPTS, 1)
        .execute();
    return id;
  }

  /**
   * 再投入が条件の関数に渡す出版の代わり。{@link EventPublication} の既定のメソッドは本物を呼ぶ。
   *
   * @param publication {@link EventPublication} かその下位の型の mock。{@link Mockito#CALLS_REAL_METHODS}
   *     で作る
   * @param lastResubmissionDate 最後の再投入の時刻。再投入していなければ {@code null}
   */
  /* package */ static <T extends EventPublication> T stubPublication(
      final T publication,
      final UUID id,
      final int completionAttempts,
      final Instant publicationDate,
      final @Nullable Instant lastResubmissionDate) {
    Mockito.doReturn(id).when(publication).getIdentifier();
    Mockito.doReturn(completionAttempts).when(publication).getCompletionAttempts();
    Mockito.doReturn(publicationDate).when(publication).getPublicationDate();
    Mockito.doReturn(lastResubmissionDate).when(publication).getLastResubmissionDate();
    return publication;
  }

  /** {@link #stubPublication} と同じ値を返し、リスナーの ID を持つ出版。 */
  /* package */ static TargetEventPublication stubTargetPublication(
      final String listenerId,
      final int completionAttempts,
      final Instant publicationDate,
      final Instant lastResubmissionDate) {
    final TargetEventPublication publication =
        Mockito.mock(TargetEventPublication.class, Mockito.CALLS_REAL_METHODS);
    Mockito.doReturn(PublicationTargetIdentifier.of(listenerId))
        .when(publication)
        .getTargetIdentifier();
    return stubPublication(
        publication, UUID.randomUUID(), completionAttempts, publicationDate, lastResubmissionDate);
  }

  /** {@code publicationId} の出版についての、上限に達した出版の ERROR。 */
  /* package */ static List<LogRecordData> exhaustedErrors(
      final CapturedLogRecords capturedLogRecords, final String publicationId) {
    return capturedLogRecords.withBody("Event publication resubmission exhausted").stream()
        .filter(record ->
            publicationId.equals(
                record.getAttributes().get(AttributeKey.stringKey("event_publication.id"))))
        .toList();
  }
}
