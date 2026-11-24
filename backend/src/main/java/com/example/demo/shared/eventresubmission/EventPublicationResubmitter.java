package com.example.demo.shared.eventresubmission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.support.LockException;
import org.jooq.DSLContext;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 失敗したイベント出版を定期的に再投入するジョブ（ADR-079）。
 *
 * <p>ShedLock のロックを取れたインスタンスだけが、リスナーの方針（{@link ResubmissionPolicies}）の待ち時間を過ぎ、再投入の回数が上限に達していない
 * {@code FAILED} の出版を再投入する。上限の回数目の再投入で失敗した出版は、Spring Modulith が {@code abandon-after-attempts} で
 * {@code ABANDONED} にする。 件数と最古の経過時間の Gauge と、上限を超えた {@code FAILED} の出版が現れたときの ERROR
 * は、ロックの外ですべてのインスタンスが出す（{@link FailedPublicationGauges}）。
 */
@Slf4j
@Component
class EventPublicationResubmitter {

  /** ジョブの実行の回数の Counter の名前。 */
  private static final String RUNS_METRIC = "event.publication.resubmission.runs";

  /** ロックを取って、失敗した出版を再投入する。 */
  private final FailedPublicationSweep sweep;

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

  /** 失敗した出版の件数と最古の経過時間の Gauge。 */
  private final FailedPublicationGauges gauges;

  /** 再投入した出版の覚え書きと、結果の読み戻し。 */
  private final ResubmissionTracker tracker;

  /* package */ EventPublicationResubmitter(
      final DSLContext dsl,
      final FailedEventPublications failedEventPublications,
      final LockingTaskExecutor eventPublicationLockingTaskExecutor,
      final ResubmissionPolicies policies,
      final Clock clock,
      final MeterRegistry meterRegistry,
      final EventPublicationResubmissionConfig.Properties properties,
      final EventPublicationResubmissionConfig.AbandonProperties abandon) {
    this.sweep =
        new FailedPublicationSweep(
            dsl, failedEventPublications, eventPublicationLockingTaskExecutor, clock, properties);
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
    this.gauges = new FailedPublicationGauges(dsl, meterRegistry, abandon);
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
      gauges.refresh(now);
      tracker.reconcile(now);
      final FailedPublicationSweep.Attempt result =
          sweep.sweep(
              dueAt(now), new FailedPublicationSweep.LockMode.TryOnce(properties.lockAtLeastFor()));
      if (!(result instanceof FailedPublicationSweep.Attempt.Swept swept)) {
        skippedRuns.increment();
        log.debug("Event publication resubmission skipped");
        return;
      }
      tracker.remember(swept.advanced(), now);
      final int resubmitted = swept.advanced().size();
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

  /**
   * 待ち時間を過ぎた出版を選ぶ条件。
   *
   * <p>ponytail: 読んだ一覧は古くなる。実行が長引くと、その間に再び FAILED に戻った行を、一覧が古いまま回数の上限を超えて再投入しうる。 実行の長さを
   * lockAtMostFor の半分までに抑え、超えた後の行は候補にせず次の回に回す（古い行から取るので飢えない）。 実行が固まっても lockAtMostFor
   * でロックは切れるため、KeepAliveLockProvider で延長しない。
   * 手での入口（EventPublicationResubmitOnce）は完了を待つ設計なので、この予算を適用しない。
   */
  private Predicate<EventPublication> dueAt(final Instant now) {
    final Duration budget = properties.lockAtMostFor().dividedBy(2);
    return publication ->
        Duration.between(now, Instant.now(clock)).compareTo(budget) <= 0
            && policies.isDue(publication, now);
  }
}
