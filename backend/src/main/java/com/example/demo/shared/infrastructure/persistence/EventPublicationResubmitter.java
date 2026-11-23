package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.support.LockException;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 失敗したイベント出版を定期的に再投入するジョブ（ADR-079）。
 *
 * <p>ShedLock のロックを取れたインスタンスだけが、リスナーの方針（{@link ResubmissionPolicies}）の待ち時間を過ぎ、再投入の回数が上限に達していない
 * {@code FAILED} の出版を再投入する。上限の回数目の再投入で失敗した出版は、Spring Modulith が {@code abandon-after-attempts} で
 * {@code ABANDONED} にする。 件数と最古の経過時間の Gauge は、ロックの外ですべてのインスタンスが集計する。
 */
@Slf4j
@Component
class EventPublicationResubmitter {

  /** 定期の再投入と手での 1 回の再投入が共有するロックの名前。 */
  /* package */ static final String LOCK_NAME = "event-publication-resubmission";

  /** ジョブの実行の回数の Counter の名前。 */
  private static final String RUNS_METRIC = "event.publication.resubmission.runs";

  /** 状態の tag の名前。 */
  private static final String STATE = "state";

  /** Spring Boot が構成した jOOQ。 */
  private final DSLContext dsl;

  /** Spring Modulith の失敗した出版の再投入。 */
  private final FailedEventPublications failedEventPublications;

  /** ロックの実行。 */
  private final LockingTaskExecutor lockingTaskExecutor;

  /** リスナーの再投入の方針。 */
  private final ResubmissionPolicies policies;

  /** 現在時刻。 */
  private final Clock clock;

  /** 設定値。 */
  private final EventPublicationResubmissionConfig.Properties properties;

  /** ロックを取って最後まで実行した回。 */
  private final Counter completedRuns;

  /** 他のインスタンスがロックを持っていて省いた回。 */
  private final Counter skippedRuns;

  /** DB やロックの既知の失敗と、予期しない例外で失敗した回。 */
  private final Counter failedRuns;

  /** 再投入した（{@code RESUBMITTED} に進んだ）出版の数。 */
  private final Counter resubmissions;

  /** 自動の再投入を待つ FAILED の出版の Gauge。 */
  private final StateGauges retrying;

  /** 上限に達した ABANDONED の出版の Gauge。 */
  private final StateGauges exhausted;

  /** 再投入した出版の覚え書きと、結果の読み戻し。 */
  private final ResubmissionTracker tracker;

  /* package */ EventPublicationResubmitter(
      final DSLContext dsl,
      final FailedEventPublications failedEventPublications,
      final LockingTaskExecutor eventPublicationLockingTaskExecutor,
      final ResubmissionPolicies policies,
      final Clock clock,
      final MeterRegistry meterRegistry,
      final EventPublicationResubmissionConfig.Properties properties) {
    this.dsl = dsl;
    this.failedEventPublications = failedEventPublications;
    this.lockingTaskExecutor = eventPublicationLockingTaskExecutor;
    this.policies = policies;
    this.clock = clock;
    this.properties = properties;
    this.tracker = new ResubmissionTracker(dsl, meterRegistry, properties);
    this.completedRuns = runs(meterRegistry, "completed");
    this.skippedRuns = runs(meterRegistry, "skipped");
    this.failedRuns = runs(meterRegistry, "failed");
    this.resubmissions =
        Counter.builder("event.publication.resubmissions")
            .description("再投入した（RESUBMITTED に進んだ）出版の数")
            .register(meterRegistry);
    this.retrying = StateGauges.register(meterRegistry, "retrying");
    this.exhausted = StateGauges.register(meterRegistry, "exhausted");
  }

  private static Counter runs(final MeterRegistry meterRegistry, final String outcome) {
    return Counter.builder(RUNS_METRIC).tag("outcome", outcome).register(meterRegistry);
  }

  /**
   * ロックを取れたら、待ち時間を過ぎた失敗した出版を再投入する。投げずに戻る。
   *
   * <p>DB やロックの既知の失敗は WARN で、予期しない実行時の例外は ERROR の {@code crashed} で記録し、どちらも {@code failed} の回に数える。
   */
  // スケジューラの既定のエラーハンドラに例外を漏らすと、ログは出ても runs のどの tag も動かず、停止に気づけないため、予期しない実行時の例外も受けて数える。
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @Scheduled(
      fixedDelayString = "${event-publication.resubmission.interval}",
      initialDelayString = "${event-publication.resubmission.interval}")
  /* package */ void resubmitOnce() {
    try {
      final Instant now = Instant.now(clock);
      refreshGauges(now);
      tracker.reconcile(now);
      final AtomicReference<@Nullable Integer> run = new AtomicReference<>();
      lockingTaskExecutor.executeWithLock(
          (Runnable) () -> run.set(resubmit(now)),
          new LockConfiguration(
              now, LOCK_NAME, properties.lockAtMostFor(), properties.lockAtLeastFor()));
      final @Nullable Integer resubmitted = run.get();
      if (resubmitted == null) {
        skippedRuns.increment();
        log.debug("Event publication resubmission skipped");
        return;
      }
      // 解放のあとに数え、解放に失敗した回を completed と failed の両方に数えない。
      resubmissions.increment(resubmitted);
      if (resubmitted > 0) {
        log.atInfo()
            .addKeyValue("event_publication.resubmitted.count", resubmitted)
            .log("Event publications resubmitted");
      }
      completedRuns.increment();
    } catch (org.springframework.dao.DataAccessException
        | org.jooq.exception.DataAccessException
        | LockException e) {
      failedRuns.increment();
      log.atWarn().setCause(e).log("Event publication resubmission failed");
    } catch (RuntimeException e) {
      failedRuns.increment();
      log.atError().setCause(e).log("Event publication resubmission crashed");
    }
  }

  private int resubmit(final Instant now) {
    // 候補にした出版の ID と、再投入の前の completion_attempts。
    final Map<UUID, Integer> candidates = new ConcurrentHashMap<>();
    // ponytail: LIMIT が条件の関数より先に掛かるため（ResubmissionOptionsLearningTest の
    // batchSizeLimitIsAppliedBeforeFilter）、毎回 FAILED の行をすべて読む。
    // withMinAge は publication_date から測り、最後の試行からの待ち時間を表せないため呼ばない。
    // withBatchSize(Integer.MAX_VALUE) で全件をヒープへ読むため、メモリの上限は FAILED の出版の数に比例する。
    // 上限の回数目の再投入で失敗した出版は ABANDONED になるため、FAILED に溜まるのは待ち時間の中の出版と、最後の試行の途中で落ちた出版である。
    // ponytail: 読んだ一覧は古くなる。実行が長引くと、その間に再び FAILED に戻った行を、一覧が古いまま回数の上限を超えて再投入しうる。
    // 実行の長さを lockAtMostFor の半分までに抑え、超えた後の行は候補にせず次の回に回す（古い行から取るので飢えない）。
    // 実行が固まっても lockAtMostFor でロックは切れるため、KeepAliveLockProvider で延長しない。
    // 手での入口（EventPublicationResubmitOnce）は完了を待つ設計なので、この予算を適用しない。
    final Duration budget = properties.lockAtMostFor().dividedBy(2);
    final AtomicInteger exhausted = new AtomicInteger();
    failedEventPublications.resubmit(
        ResubmissionOptions.defaults()
            .withBatchSize(Integer.MAX_VALUE)
            .withFilter(publication -> {
              if (policies.isExhausted(publication)) {
                exhausted.incrementAndGet();
                return false;
              }
              return Duration.between(now, Instant.now(clock)).compareTo(budget) <= 0
                  && policies.isDue(publication, now)
                  && candidates.size() < properties.maxPerRun()
                  && candidates.put(
                          publication.getIdentifier(), publication.getCompletionAttempts())
                      == null;
            }));
    if (exhausted.get() > 0) {
      // 失敗を記録しない試行で上限を超えた出版は ABANDONED にならず、ERROR も出ないため、ロックを取った回ごとに件数で知らせる。
      log.atError()
          .addKeyValue("event_publication.exhausted.count", exhausted.get())
          .log("Failed event publications exceed the resubmission limit");
    }
    return tracker.track(candidates, now);
  }

  /**
   * FAILED の出版と、上限に達した ABANDONED の出版の件数と最古の経過時間を集計し、Gauge の値にする。
   *
   * <p>{@code completion-mode: archive} では、ABANDONED の出版は archive の表にある。 DB の既知の失敗は WARN で記録し、Gauge
   * は前の値のまま再投入を続ける。 Gauge の集計は表示と警報のためで、再投入の前提ではないためである。
   */
  private void refreshGauges(final Instant now) {
    try {
      retrying.set(
          dsl.select(DSL.count(), DSL.min(EVENT_PUBLICATION.PUBLICATION_DATE))
              .from(EVENT_PUBLICATION)
              .where(EVENT_PUBLICATION.STATUS.eq(EventPublication.Status.FAILED.name()))
              .fetchSingle(),
          now);
      // 条件を部分索引 event_publication_archive_abandoned_idx の述語と同じにし、archive の全行を読まない。
      exhausted.set(
          dsl.select(DSL.count(), DSL.min(EVENT_PUBLICATION_ARCHIVE.PUBLICATION_DATE))
              .from(EVENT_PUBLICATION_ARCHIVE)
              .where(EVENT_PUBLICATION_ARCHIVE.STATUS.eq(EventPublication.Status.ABANDONED.name()))
              .fetchSingle(),
          now);
    } catch (org.springframework.dao.DataAccessException
        | org.jooq.exception.DataAccessException e) {
      log.atWarn().setCause(e).log("Event publication gauges refresh failed");
    }
  }

  /**
   * 1 つの {@code state} の、件数と最古の経過秒数の Gauge。
   *
   * <p>Micrometer は状態のオブジェクトを弱い参照で持つため、final のフィールドで持つ。
   *
   * @param count 件数
   * @param oldestAge 最も古い出版の経過秒数
   */
  private record StateGauges(AtomicLong count, AtomicLong oldestAge) {

    private static StateGauges register(final MeterRegistry meterRegistry, final String state) {
      final StateGauges gauges = new StateGauges(new AtomicLong(), new AtomicLong());
      Gauge.builder("event.publication.failed", gauges.count, AtomicLong::get)
          .tag(STATE, state)
          .register(meterRegistry);
      TimeGauge.builder(
              "event.publication.failed.oldest.age",
              gauges.oldestAge,
              TimeUnit.SECONDS,
              AtomicLong::get)
          .tag(STATE, state)
          .register(meterRegistry);
      return gauges;
    }

    /** 件数と最も古い出版の時刻から値を決める。時計が出版より前なら経過秒数を 0 にする。 */
    private void set(final Record2<Integer, Instant> countAndOldest, final Instant now) {
      final @Nullable Instant oldest = countAndOldest.value2();
      count.set(countAndOldest.value1());
      oldestAge.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toSeconds()));
    }
  }
}
