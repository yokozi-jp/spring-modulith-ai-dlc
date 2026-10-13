package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.jooq.DSLContext;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 失敗したイベント出版を、運用者の指定で 1 回だけ再投入し、リスナーの処理が終わるまで待つ（ADR-079）。
 *
 * <p>定期の再投入の上限に達した出版を、原因を直したあとに手で再投入する入口で使う。待ち時間と回数の上限は見ず、対象に合う {@code FAILED} の出版を {@code
 * max-per-run} 件まで再投入する。定期の再投入と同じロックを取り、取れるまで {@code lock-at-most-for} を上限に待つ。
 * リスナーは別のスレッドで動くため、再投入した出版が {@code PUBLISHED}、{@code PROCESSING}、{@code RESUBMITTED}
 * でなくなるまで待ち、件数を記録する。
 *
 * <p>定期の再投入にある {@code lock-at-most-for} の半分の時間予算は適用しない。この入口は完了を待つ設計で、{@code max-per-run} だけで件数を抑える。
 */
// 定期の再投入の上限に達した出版を手で再投入する運用者の入口で、docs/backend/class-roles/index.md にない役割である。
// 結合テストが他のモジュールのリスナーの回復を確かめるため public にする。他のモジュールの本番のコードからは ArchUnit が使わせない。
@Slf4j
@Component
public class EventPublicationResubmitOnce {

  /** ロックと完了を確かめる間隔。 */
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  /** すべての FAILED の出版を対象にする指定。 */
  private static final String ALL = "all";

  /** 方針の名前で対象を選ぶ指定の接頭辞。 */
  private static final String POLICY_PREFIX = "policy:";

  /** イベントの完全修飾名で対象を選ぶ指定の接頭辞。 */
  private static final String EVENT_TYPE_PREFIX = "event-type:";

  /** 処理が終わっていない状態。 */
  private static final List<String> IN_FLIGHT = List.of("PUBLISHED", "PROCESSING", "RESUBMITTED");

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

  /** 定期の再投入の設定値。 */
  private final EventPublicationResubmissionConfig.Properties properties;

  /** 手での 1 回の再投入の設定値。 */
  private final EventPublicationResubmissionConfig.ResubmitOnceProperties onceProperties;

  /* package */ EventPublicationResubmitOnce(
      final DSLContext dsl,
      final FailedEventPublications failedEventPublications,
      final LockingTaskExecutor eventPublicationLockingTaskExecutor,
      final ResubmissionPolicies policies,
      final Clock clock,
      final EventPublicationResubmissionConfig.Properties properties,
      final EventPublicationResubmissionConfig.ResubmitOnceProperties onceProperties) {
    this.dsl = dsl;
    this.failedEventPublications = failedEventPublications;
    this.lockingTaskExecutor = eventPublicationLockingTaskExecutor;
    this.policies = policies;
    this.clock = clock;
    this.properties = properties;
    this.onceProperties = onceProperties;
  }

  /**
   * 対象の FAILED の出版を再投入し、リスナーの処理が終わるか {@code completion-timeout} を過ぎるまで待つ。
   *
   * @param target {@code all}、{@code policy:<方針の名前>}、{@code event-type:<イベントの完全修飾名>} のいずれか
   * @return 再投入した件数と、その処理の結果
   * @throws IllegalArgumentException 対象の指定が不正なとき
   * @throws IllegalStateException トランザクションの中で呼んだとき、{@code lock-at-most-for} の間にロックを取れなかったとき
   */
  public Outcome run(final String target) {
    // トランザクションの中で呼ぶと、再投入の印がコミットされず、別のスレッドのリスナーが進めないまま待ち続ける。
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("resubmit-once must not run inside a transaction");
    }
    final Predicate<EventPublication> selected = selector(target);
    final List<UUID> resubmitted = new CopyOnWriteArrayList<>();
    final AtomicInteger remaining = new AtomicInteger();
    final int lockAttempts = pollsWithin(properties.lockAtMostFor());
    boolean locked = tryResubmit(selected, resubmitted, remaining);
    for (int attempt = 0; attempt < lockAttempts && !locked; attempt++) {
      sleep();
      locked = tryResubmit(selected, resubmitted, remaining);
    }
    if (!locked) {
      throw new IllegalStateException(
          "lock "
              + EventPublicationResubmitter.LOCK_NAME
              + " was not acquired within "
              + properties.lockAtMostFor());
    }
    final Outcome result = awaitCompletion(List.copyOf(resubmitted), remaining.get());
    record(result);
    return result;
  }

  private Predicate<EventPublication> selector(final String target) {
    if (ALL.equals(target)) {
      return publication -> true;
    }
    if (target.startsWith(POLICY_PREFIX)) {
      final String name = target.substring(POLICY_PREFIX.length());
      if (!properties.policies().containsKey(name)) {
        throw new IllegalArgumentException(
            "unknown resubmission policy: " + name + " " + properties.policies().keySet());
      }
      return publication ->
          name.equals(policies.nameOf(ResubmissionPolicies.listenerIdOf(publication)));
    }
    if (target.startsWith(EVENT_TYPE_PREFIX) && target.length() > EVENT_TYPE_PREFIX.length()) {
      final String eventType = target.substring(EVENT_TYPE_PREFIX.length());
      return publication -> eventType.equals(publication.getEvent().getClass().getName());
    }
    throw new IllegalArgumentException(
        "event-publication.resubmit-once.target must be all, policy:<name> or event-type:<class>: "
            + target);
  }

  /** 結果の件数を記録する。すべて完了したら INFO、それ以外は WARN にする。 */
  private static void record(final Outcome result) {
    log.atLevel(result.succeeded() ? org.slf4j.event.Level.INFO : org.slf4j.event.Level.WARN)
        .addKeyValue("event_publication.resubmitted.count", result.resubmitted())
        .addKeyValue("event_publication.completed.count", result.completed())
        .addKeyValue("event_publication.incomplete.count", result.incomplete())
        .addKeyValue("event_publication.remaining.count", result.remaining())
        .log(
            result.succeeded()
                ? "Event publications resubmitted once"
                : "Event publication resubmit-once incomplete");
  }

  /** ロックを取れたら再投入し、取れたかを返す。下限は置かず、終わったらすぐ放す。 */
  private boolean tryResubmit(
      final Predicate<EventPublication> selected,
      final List<UUID> resubmitted,
      final AtomicInteger remaining) {
    final AtomicBoolean locked = new AtomicBoolean();
    lockingTaskExecutor.executeWithLock(
        (Runnable)
            () -> {
              locked.set(true);
              resubmit(selected, resubmitted, remaining);
            },
        new LockConfiguration(
            Instant.now(clock),
            EventPublicationResubmitter.LOCK_NAME,
            properties.lockAtMostFor(),
            Duration.ZERO));
    return locked.get();
  }

  /** 対象に合う出版を max-per-run 件まで再投入し、ID を集める。超えた件数は remaining に数える。 */
  private void resubmit(
      final Predicate<EventPublication> selected,
      final List<UUID> resubmitted,
      final AtomicInteger remaining) {
    // 定期の再投入と同じく、LIMIT が条件の関数より先に掛かるのを避けるため、FAILED の行をすべて読む。
    failedEventPublications.resubmit(
        ResubmissionOptions.defaults()
            .withBatchSize(Integer.MAX_VALUE)
            .withFilter(
                publication -> {
                  if (!selected.test(publication)) {
                    return false;
                  }
                  if (resubmitted.size() >= properties.maxPerRun()) {
                    remaining.incrementAndGet();
                    return false;
                  }
                  resubmitted.add(publication.getIdentifier());
                  return true;
                }));
  }

  /** 再投入した出版の処理が終わるまで待ち、結果を数える。完了した出版は未完了の表から消えるため、残った行で数える。 */
  private Outcome awaitCompletion(final List<UUID> ids, final int remaining) {
    final int polls = pollsWithin(onceProperties.completionTimeout());
    Map<String, Integer> byStatus = countByStatus(ids);
    for (int poll = 0; poll < polls && inFlight(byStatus) > 0; poll++) {
      sleep();
      byStatus = countByStatus(ids);
    }
    final int left = byStatus.values().stream().mapToInt(Integer::intValue).sum();
    return new Outcome(ids.size(), ids.size() - left, left, remaining);
  }

  private Map<String, Integer> countByStatus(final List<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return dsl.select(EVENT_PUBLICATION.STATUS, org.jooq.impl.DSL.count())
        .from(EVENT_PUBLICATION)
        .where(EVENT_PUBLICATION.ID.in(ids))
        .groupBy(EVENT_PUBLICATION.STATUS)
        .fetchMap(record -> String.valueOf(record.value1()), record -> record.value2());
  }

  private static int inFlight(final Map<String, Integer> byStatus) {
    return IN_FLIGHT.stream().mapToInt(status -> byStatus.getOrDefault(status, 0)).sum();
  }

  /**
   * {@code limit} の間に確かめる回数。int を超える上限は切り捨てず {@link ArithmeticException} で失敗する。
   *
   * <p>回数を int にするのは、PIT が int のループの増分の変異を無限ループとして除くためである。long の増分は除かれず、待ちの上限まで止まる。
   */
  private static int pollsWithin(final Duration limit) {
    return Math.toIntExact(limit.dividedBy(POLL_INTERVAL));
  }

  // ロックの解放とリスナーの完了を、短い間隔で確かめるため待つ。
  @SuppressWarnings("PMD.DoNotUseThreads")
  private static void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while resubmitting once", e);
    }
  }

  /**
   * 1 回の再投入の結果。
   *
   * @param resubmitted 再投入した出版の数
   * @param completed そのうち処理を完了した数
   * @param incomplete そのうち FAILED に戻った数と、待つ上限を過ぎても処理中の数
   * @param remaining 対象に合うが、{@code max-per-run} を超えたため再投入しなかった数
   */
  public record Outcome(int resubmitted, int completed, int incomplete, int remaining) {

    /** 再投入した出版がすべて完了し、残りもないか。 */
    public boolean succeeded() {
      return incomplete == 0 && remaining == 0;
    }
  }
}
