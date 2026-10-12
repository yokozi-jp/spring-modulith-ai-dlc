package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
 * 失敗したイベント出版を定期的に再投入するジョブ（ADR-077）。
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

  /** 再投入の候補にした出版の数。 */
  private final Counter resubmissions;

  /** 自動の再投入を待つ FAILED の出版の数。 */
  private final AtomicLong retrying = new AtomicLong();

  /** 上限に達した FAILED の出版の数。 */
  private final AtomicLong exhausted = new AtomicLong();

  /** 自動の再投入を待つ最も古い出版の経過秒数。 */
  private final AtomicLong retryingOldestAge = new AtomicLong();

  /** 上限に達した最も古い出版の経過秒数。 */
  private final AtomicLong exhaustedOldestAge = new AtomicLong();

  /** このインスタンスで ERROR を記録した、上限に達した出版の ID。 */
  private final Set<UUID> reported = ConcurrentHashMap.newKeySet();

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
    this.completedRuns = runs(meterRegistry, "completed");
    this.skippedRuns = runs(meterRegistry, "skipped");
    this.failedRuns = runs(meterRegistry, "failed");
    this.resubmissions =
        Counter.builder("event.publication.resubmissions")
            .description("再投入の候補にした出版の数。ロックの中では再投入した数と一致する")
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
      final AtomicReference<@Nullable Run> run = new AtomicReference<>();
      lockingTaskExecutor.executeWithLock(
          (Runnable) () -> run.set(new Run(reportExhausted(), resubmit(now))),
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
      if (done.resubmitted() > 0 || done.exhausted() > 0) {
        log.atInfo()
            .addKeyValue("event_publication.resubmitted.count", done.resubmitted())
            .addKeyValue("event_publication.exhausted.count", done.exhausted())
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
    final AtomicInteger candidates = new AtomicInteger();
    // ponytail: 2.1.1 では LIMIT が条件の関数より先に掛かり、withMinAge も FAILED に効かないため、毎回 FAILED
    // の行をすべて読む（ResubmissionQuirksLearningTest）。行が大量に溜まると毎回の読み込みが重くなる。
    // 上限に達した出版は Runbook で片付ける。Spring Modulith が回数の条件を SQL へ渡せるようになったら LIMIT にする。
    failedEventPublications.resubmit(
        ResubmissionOptions.defaults()
            .withMinAge(properties.waitAge())
            .withBatchSize(Integer.MAX_VALUE)
            .withFilter(
                publication ->
                    policies.isDue(publication, now)
                        && candidates.get() < properties.maxPerRun()
                        && candidates.incrementAndGet() > 0));
    return candidates.get();
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

  /** 上限に達した出版のうち、このインスタンスでまだ記録していないものを ERROR で記録し、上限に達した件数を返す。 */
  private int reportExhausted() {
    // serialized_event はイベントの本文なので読まない（ADR-015）。
    final Result<Record5<UUID, String, String, Instant, Integer>> rows =
        dsl.select(
                EVENT_PUBLICATION.ID,
                EVENT_PUBLICATION.EVENT_TYPE,
                EVENT_PUBLICATION.LISTENER_ID,
                EVENT_PUBLICATION.PUBLICATION_DATE,
                EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.STATUS.eq(FAILED).and(exhaustedCondition()))
            .orderBy(EVENT_PUBLICATION.PUBLICATION_DATE)
            .fetch();
    // ponytail: 記録済みの ID はインスタンスのメモリにあるため、同じ出版の ERROR はロックを取ったインスタンスの数と再起動の回数だけ重なりうる。
    // 監視の通知は event_publication.id でまとめる（Runbook）。重複を DB で防ぐ必要が出たら、記録済みの ID を持つ表を足す。
    reported.retainAll(rows.stream().map(Record5::value1).collect(Collectors.toSet()));
    int reportedThisRun = 0;
    for (final Record5<UUID, String, String, Instant, Integer> row : rows) {
      if (reportedThisRun >= properties.maxPerRun()) {
        break;
      }
      if (reported.add(row.value1())) {
        reportedThisRun++;
        log.atError()
            .addKeyValue("event_publication.id", row.value1().toString())
            .addKeyValue("event_publication.event_type", row.value2())
            .addKeyValue("event_publication.listener_id", row.value3())
            .addKeyValue("event_publication.completion_attempts", row.value5())
            .log("Event publication resubmission exhausted");
      }
    }
    return rows.size();
  }

  /**
   * ロックの中の 1 回の実行の結果。
   *
   * @param exhausted 上限に達した出版の件数
   * @param resubmitted 再投入の候補にした出版の件数
   */
  private record Run(int exhausted, int resubmitted) {}
}
