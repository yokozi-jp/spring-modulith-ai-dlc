package com.example.demo.shared.eventresubmission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.jooq.DSLContext;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;

/**
 * ロックを取り、選んだ {@code FAILED} の出版を {@code max-per-run} 件まで再投入し、{@code RESUBMITTED}
 * へ進んだ出版を返す（ADR-079）。
 *
 * <p>定期の再投入（{@link EventPublicationResubmitter}）と手での 1 回の再投入（{@link
 * EventPublicationResubmitOnce}）は、選ぶ条件とロックの 取り方（{@link LockMode}）、再投入のあとの扱いだけが違い、ほかはここに集める。
 *
 * <p>{@code FailedEventPublications.resubmit} は件数を返さず、{@code markResubmitted} は他の更新で偽になりうる（{@code
 * completion_date} が空で {@code RESUBMITTED} でない行だけを更新する）。そのため、再投入のあとに読み戻し、{@code
 * completion_attempts} が増えた行と、すでに完了するか ABANDONED になって archive に移った行を「進んだ」とする。進んだ行は {@code
 * markResubmitted} が必ず {@code completion_attempts} を 1 増やす。
 */
final class FailedPublicationSweep {

  /** 定期の再投入と手での 1 回の再投入が共有するロックの名前。 */
  /* package */ static final String LOCK_NAME = "event-publication-resubmission";

  /** Spring Boot が構成した jOOQ。 */
  private final DSLContext dsl;

  /** Spring Modulith の失敗した出版の再投入。 */
  private final FailedEventPublications failedEventPublications;

  /** ロックの実行。 */
  private final LockingTaskExecutor lockingTaskExecutor;

  /** 現在時刻。 */
  private final Clock clock;

  /** 設定値。 */
  private final EventPublicationResubmissionConfig.Properties properties;

  /* package */ FailedPublicationSweep(
      final DSLContext dsl,
      final FailedEventPublications failedEventPublications,
      final LockingTaskExecutor eventPublicationLockingTaskExecutor,
      final Clock clock,
      final EventPublicationResubmissionConfig.Properties properties) {
    this.dsl = dsl;
    this.failedEventPublications = failedEventPublications;
    this.lockingTaskExecutor = eventPublicationLockingTaskExecutor;
    this.clock = clock;
    this.properties = properties;
  }

  /** ロックの取り方。 */
  /* package */ sealed interface LockMode {

    /** 1 回だけ試し、他のインスタンスが持っていれば省く。{@code atLeastFor} は、終わってもロックを持ち続ける下限。 */
    record TryOnce(Duration atLeastFor) implements LockMode {}

    /** 取れるまで {@code lock-at-most-for} を上限に待つ。下限は置かず、終わったらすぐ放す。 */
    record WaitUpTo() implements LockMode {}
  }

  /** 実行の結果（再投入した、またはロックを取れず省いた）。 */
  /* package */ sealed interface Attempt {

    /** ロックを取れず、再投入しなかった。 */
    record Skipped() implements Attempt {}

    /**
     * ロックを取って再投入した。
     *
     * @param advanced 実際に {@code RESUBMITTED} へ進んだ（または、すでに archive へ移った）出版の ID
     * @param overLimit 選ばれたが {@code max-per-run} を超えたため再投入しなかった数
     */
    record Swept(Set<UUID> advanced, int overLimit) implements Attempt {

      public Swept {
        advanced = Set.copyOf(advanced);
      }
    }
  }

  /**
   * ロックを取り、{@code selection} に合う出版を {@code max-per-run} 件まで再投入する。
   *
   * <p>{@code FailedEventPublications.resubmit} は {@code LIMIT} を条件の関数より先に掛けるため（{@link
   * ResubmissionOptionsLearningTest} の batchSizeLimitIsAppliedBeforeFilter）、毎回 FAILED の行をすべて読む。
   * {@code withBatchSize(Integer.MAX_VALUE)} で全件をヒープへ読むため、メモリの上限は FAILED の出版の数に比例する。 {@code
   * withMinAge} は publication_date から測り、最後の試行からの待ち時間を表せないため呼ばない。
   */
  /* package */ Attempt sweep(final Predicate<EventPublication> selection, final LockMode lock) {
    final Map<UUID, Integer> candidates = new ConcurrentHashMap<>();
    final AtomicInteger overLimit = new AtomicInteger();
    final Predicate<EventPublication> limited =
        publication -> {
          if (!selection.test(publication)) {
            return false;
          }
          if (candidates.size() >= properties.maxPerRun()) {
            overLimit.incrementAndGet();
            return false;
          }
          // 再投入の前の completion_attempts を覚え、あとで進んだかを判定する。
          candidates.put(publication.getIdentifier(), publication.getCompletionAttempts());
          return true;
        };
    final Runnable resubmit =
        () ->
            failedEventPublications.resubmit(
                ResubmissionOptions.defaults()
                    .withBatchSize(Integer.MAX_VALUE)
                    .withFilter(limited));
    final boolean locked =
        switch (lock) {
          case LockMode.TryOnce once -> tryLocked(resubmit, once.atLeastFor());
          case LockMode.WaitUpTo _ -> waitLocked(resubmit);
        };
    return locked
        ? new Attempt.Swept(advancedAmong(candidates), overLimit.get())
        : new Attempt.Skipped();
  }

  private boolean waitLocked(final Runnable resubmit) {
    final int attempts = Polling.pollsWithin(properties.lockAtMostFor());
    boolean locked = tryLocked(resubmit, Duration.ZERO);
    for (int attempt = 0; attempt < attempts && !locked; attempt++) {
      Polling.sleep();
      locked = tryLocked(resubmit, Duration.ZERO);
    }
    return locked;
  }

  /** ロックを取れたら {@code task} を実行し、取れたかを返す。 */
  private boolean tryLocked(final Runnable task, final Duration atLeastFor) {
    final AtomicBoolean locked = new AtomicBoolean();
    lockingTaskExecutor.executeWithLock(
        (Runnable)
            () -> {
              locked.set(true);
              task.run();
            },
        new LockConfiguration(
            Instant.now(clock), LOCK_NAME, properties.lockAtMostFor(), atLeastFor));
    return locked.get();
  }

  /** 候補のうち、実際に {@code RESUBMITTED} へ進んだ出版の ID を返す。 */
  private Set<UUID> advancedAmong(final Map<UUID, Integer> candidates) {
    final Map<UUID, EventPublicationStates.State> states =
        EventPublicationStates.read(dsl, candidates.keySet());
    return candidates.entrySet().stream()
        .filter(candidate -> {
          final EventPublicationStates.State state = states.get(candidate.getKey());
          return state != null
              && (state.isArchived() || state.completionAttempts() > candidate.getValue());
        })
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }
}
