package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.support.LockException;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record4;
import org.jooq.Record5;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 失敗したイベント出版を定期的に再投入するジョブ（ADR-079）。
 *
 * <p>ShedLock のロックを取れたインスタンスだけが、リスナーの方針（{@link ResubmissionPolicies}）の待ち時間を過ぎ、再投入の回数が上限未満の {@code
 * FAILED} の出版を再投入する。上限に達した出版は ERROR のログで知らせ、自動の対象から外す。 件数と最古の経過時間の Gauge は、ロックの外ですべてのインスタンスが集計する。
 */
@Slf4j
@Component
class EventPublicationResubmitter {

  /** 定期の再投入と手での 1 回の再投入が共有するロックの名前。 */
  /* package */ static final String LOCK_NAME = "event-publication-resubmission";

  /** Spring Modulith の失敗した出版の状態。 */
  private static final String FAILED = "FAILED";

  /** ジョブの実行の回数の Counter の名前。 */
  private static final String RUNS_METRIC = "event.publication.resubmission.runs";

  /** 上限に達した出版の取りこぼしを探す間隔。設定値にはしない。 */
  private static final Duration SWEEP_PERIOD = Duration.ofHours(1);

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

  /** 自動の再投入を待つ FAILED の出版の数。 */
  private final AtomicLong retrying = new AtomicLong();

  /** 上限に達した FAILED の出版の数。 */
  private final AtomicLong exhausted = new AtomicLong();

  /** 自動の再投入を待つ最も古い出版の経過秒数。 */
  private final AtomicLong retryingOldestAge = new AtomicLong();

  /** 上限に達した最も古い出版の経過秒数。 */
  private final AtomicLong exhaustedOldestAge = new AtomicLong();

  /** 上限に達した出版の取りこぼしを探す回の間隔。ロックを取った実行の回数で数える。 */
  private final long sweepEvery;

  /** ロックを取った実行の回数。 */
  private final AtomicLong lockedRuns = new AtomicLong();

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
    // ponytail: 時計でなく実行の回数で数え、テストを決定的にする。周期は固定で、設定にする必要が出たら Properties に足す。
    this.sweepEvery =
        Math.max(1, Math.ceilDiv(SWEEP_PERIOD.toMillis(), properties.interval().toMillis()));
    this.tracker = new ResubmissionTracker(dsl, policies, meterRegistry, properties);
    this.completedRuns = runs(meterRegistry, "completed");
    this.skippedRuns = runs(meterRegistry, "skipped");
    this.failedRuns = runs(meterRegistry, "failed");
    this.resubmissions =
        Counter.builder("event.publication.resubmissions")
            .description("再投入した（RESUBMITTED に進んだ）出版の数")
            .register(meterRegistry);
    // Micrometer は状態のオブジェクトを弱い参照で持つため、final のフィールドを渡す。
    Gauge.builder("event.publication.failed", retrying, AtomicLong::get)
        .tag(STATE, "retrying")
        .register(meterRegistry);
    Gauge.builder("event.publication.failed", exhausted, AtomicLong::get)
        .tag(STATE, "exhausted")
        .register(meterRegistry);
    TimeGauge.builder(
            "event.publication.failed.oldest.age",
            retryingOldestAge,
            TimeUnit.SECONDS,
            AtomicLong::get)
        .tag(STATE, "retrying")
        .register(meterRegistry);
    TimeGauge.builder(
            "event.publication.failed.oldest.age",
            exhaustedOldestAge,
            TimeUnit.SECONDS,
            AtomicLong::get)
        .tag(STATE, "exhausted")
        .register(meterRegistry);
  }

  private static Counter runs(final MeterRegistry meterRegistry, final String outcome) {
    return Counter.builder(RUNS_METRIC).tag("outcome", outcome).register(meterRegistry);
  }

  /**
   * ロックを取れたら、上限に達した出版を知らせ、待ち時間を過ぎた失敗した出版を再投入する。投げずに戻る。
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
      updateGauges(now);
      tracker.reconcile(now);
      final AtomicReference<@Nullable Run> run = new AtomicReference<>();
      lockingTaskExecutor.executeWithLock(
          (Runnable)
              () ->
                  run.set(
                      new Run(
                          reportExhausted(lockedRuns.getAndIncrement() % sweepEvery == 0),
                          resubmit(now))),
          new LockConfiguration(
              now, LOCK_NAME, properties.lockAtMostFor(), properties.lockAtLeastFor()));
      final @Nullable Run done = run.get();
      if (done == null) {
        skippedRuns.increment();
        log.debug("Event publication resubmission skipped");
        return;
      }
      // 解放のあとに数え、解放に失敗した回を completed と failed の両方に数えない。
      resubmissions.increment(done.resubmitted());
      if (done.resubmitted() > 0 || done.newlyReported() > 0) {
        log.atInfo()
            .addKeyValue("event_publication.resubmitted.count", done.resubmitted())
            .addKeyValue("event_publication.exhausted.count", done.newlyReported())
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
    // ponytail: 2.1.1 では LIMIT が条件の関数より先に掛かり、withMinAge も FAILED に効かないため、withMinAge は呼ばず、毎回 FAILED
    // の行をすべて読む。
    // 根拠は ResubmissionOptionsLearningTest の minAgeDoesNotFilterFailedPublications と
    // batchSizeLimitIsAppliedBeforeFilter である。
    // withBatchSize(Integer.MAX_VALUE) で全件をヒープへ読むため、メモリの上限は FAILED の出版の数に比例し、行が大量に溜まると毎回の読み込みも重くなる。
    // 上限に達した出版は Runbook で片付ける。Spring Modulith が回数の条件を SQL へ渡せるようになったら LIMIT にする。
    // ponytail: 読んだ一覧は古くなる。実行が長引くと、その間に再び FAILED に戻った行を、一覧が古いまま方針の上限を超えて再投入しうる。
    // 実行の長さを lockAtMostFor の半分までに抑え、超えた後の行は候補にせず次の回に回す（古い行から取るので飢えない）。
    // 実行が固まっても lockAtMostFor でロックは切れるため、KeepAliveLockProvider で延長しない。
    // 手での入口（EventPublicationResubmitOnce）は完了を待つ設計なので、この予算を適用しない。
    final Duration budget = properties.lockAtMostFor().dividedBy(2);
    failedEventPublications.resubmit(
        ResubmissionOptions.defaults()
            .withBatchSize(Integer.MAX_VALUE)
            .withFilter(
                publication ->
                    Duration.between(now, Instant.now(clock)).compareTo(budget) <= 0
                        && policies.isDue(publication, now)
                        && candidates.size() < properties.maxPerRun()
                        && candidates.put(
                                publication.getIdentifier(), publication.getCompletionAttempts())
                            == null));
    return tracker.track(candidates, now);
  }

  private Condition retryingCondition() {
    return EVENT_PUBLICATION.COMPLETION_ATTEMPTS.le(policies.maxResubmissions());
  }

  private Condition exhaustedCondition() {
    return EVENT_PUBLICATION.COMPLETION_ATTEMPTS.gt(policies.maxResubmissions());
  }

  /** FAILED の出版の件数と最古の経過時間を、リスナーの方針の上限で分けて集計し、Gauge の値にする。 */
  private void updateGauges(final Instant now) {
    final Record4<Integer, Instant, Integer, Instant> row =
        dsl.select(
                DSL.count().filterWhere(retryingCondition()),
                DSL.min(EVENT_PUBLICATION.PUBLICATION_DATE).filterWhere(retryingCondition()),
                DSL.count().filterWhere(exhaustedCondition()),
                DSL.min(EVENT_PUBLICATION.PUBLICATION_DATE).filterWhere(exhaustedCondition()))
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.STATUS.eq(FAILED))
            .fetchSingle();
    retrying.set(row.value1());
    retryingOldestAge.set(ageSeconds(row.value2(), now));
    exhausted.set(row.value3());
    exhaustedOldestAge.set(ageSeconds(row.value4(), now));
  }

  private static long ageSeconds(final @Nullable Instant oldest, final Instant now) {
    return oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toSeconds());
  }

  /**
   * 上限に達した出版のうち、このインスタンスでまだ記録していないものを最大 {@code max-per-run} 件まで ERROR で記録し、新しく記録した件数を返す。
   *
   * <p>2 回の読み込みに分ける。(a) 記録済みの ID のうち、行が消えたか上限未満に戻ったものを記録から外す。(b) 記録済みでない上限到達の出版を、古い順に {@code
   * max-per-run} 件だけ読む。どちらも上限に達した全件は読まない。
   *
   * <p>(a) は毎回行う。(b) は取りこぼしの回収で、ロックを取った実行の最初の回と、以後 1 時間ごと（{@code ceil(1 時間 / interval)} 回ごと）にだけ行う。
   * 再投入した出版の上限到達は {@link ResubmissionTracker} が結果の読み戻しで記録するため、(b) が拾うのは、再投入したインスタンスが落ちた出版、Staleness
   * Monitor が FAILED に戻した出版、再起動の前から残る出版である。
   */
  // ponytail: 取りこぼしの ERROR は最大で 1 時間遅れる。遅れを縮めるなら SWEEP_PERIOD を短くする（設定にするのは上位互換）。
  // 実行の回数は再起動で 0 に戻るため、起動後の最初の回で必ず 1 回回収する。
  private int reportExhausted(final boolean sweep) {
    forgetResolved();
    if (!sweep) {
      return 0;
    }
    final Set<UUID> reported = tracker.reportedIds();
    final Condition notReported =
        reported.isEmpty()
            ? DSL.noCondition()
            : EVENT_PUBLICATION.ID.ne(DSL.all(reported.toArray(UUID[]::new)));
    // serialized_event はイベントの本文なので読まない（ADR-015）。
    final Result<Record5<UUID, String, String, Instant, Integer>> rows =
        dsl.select(
                EVENT_PUBLICATION.ID,
                EVENT_PUBLICATION.EVENT_TYPE,
                EVENT_PUBLICATION.LISTENER_ID,
                EVENT_PUBLICATION.PUBLICATION_DATE,
                EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.STATUS.eq(FAILED).and(exhaustedCondition()).and(notReported))
            .orderBy(EVENT_PUBLICATION.PUBLICATION_DATE)
            .limit(properties.maxPerRun())
            .fetch();
    int newlyReported = 0;
    for (final Record5<UUID, String, String, Instant, Integer> row : rows) {
      if (tracker.report(row.value1(), row.value2(), row.value3(), row.value5())) {
        newlyReported++;
      }
    }
    return newlyReported;
  }

  /** 記録済みの ID のうち、まだ FAILED で上限に達しているものだけを残す。行が消えるか上限未満に戻った ID は、再び上限に達したら再び記録する。 */
  private void forgetResolved() {
    final Set<UUID> reported = tracker.reportedIds();
    if (reported.isEmpty()) {
      return;
    }
    // ponytail: 記録済みの ID はインスタンスのメモリにあり、大きさは放置された上限到達の出版の数に比例する。
    // 運用の上限は failed.oldest.age{state=exhausted} の 1 日の警報で、片付ける前提である。
    // 同じ出版の ERROR は、ロックを取ったインスタンスの数と再起動の回数だけ重なりうる。監視の通知は event_publication.id でまとめる（Runbook）。
    // 放置が常態になるか、重複を DB で防ぐ必要が出たら、記録済みの ID を持つ表を足す。
    // ID は 1 つの配列として bind するため、NOT IN の個別の bind と違い PostgreSQL の bind の数の上限に当たらない。
    reported.retainAll(
        dsl.select(EVENT_PUBLICATION.ID)
            .from(EVENT_PUBLICATION)
            .where(
                EVENT_PUBLICATION
                    .ID
                    .eq(DSL.any(reported.toArray(UUID[]::new)))
                    .and(EVENT_PUBLICATION.STATUS.eq(FAILED))
                    .and(exhaustedCondition()))
            .fetchSet(EVENT_PUBLICATION.ID));
  }

  /**
   * ロックの中の 1 回の実行の結果。
   *
   * @param newlyReported このインスタンスで新しく ERROR にした、上限に達した出版の件数
   * @param resubmitted 再投入した（{@code RESUBMITTED} に進んだ）出版の件数
   */
  private record Run(int newlyReported, int resubmitted) {}
}
