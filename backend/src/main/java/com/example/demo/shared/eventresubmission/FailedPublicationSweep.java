package com.example.demo.shared.eventresubmission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.stereotype.Component;

/**
 * ロックを取り、古い出版を {@code FAILED} に戻してから、選んだ {@code FAILED} の出版を {@code max-per-run} 件まで再投入し、{@code
 * RESUBMITTED} へ進んだ出版を返す（ADR-079）。
 *
 * <p>定期の再投入（{@link EventPublicationResubmitter}）は {@link #tryOnce}、手での 1 回の再投入（{@link
 * EventPublicationResubmitOnce}）は {@link #waitUpTo} で呼ぶ。2 つは選ぶ条件、ロックの取り方、再投入のあとの扱いだけが違い、ほかはここに集める。
 *
 * <p>古い出版を {@code FAILED} に戻す処理（{@link StalePublications}）は、Spring Modulith の組み込みの Staleness
 * Monitor を使わず、ここでロックの中で呼ぶ（ADR-079）。
 *
 * <p>{@code FailedEventPublications.resubmit} は件数を返さず、{@code markResubmitted} は他の更新で偽になりうる（{@code
 * completion_date} が空で {@code RESUBMITTED} でない行だけを更新する）。そのため、再投入のあとに読み戻し、{@code
 * completion_attempts} が増えた行と、すでに完了するか ABANDONED になって archive に移った行を「進んだ」とする。進んだ行は {@code
 * markResubmitted} が必ず {@code completion_attempts} を 1 増やす。
 */
@Component
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

  /** 古い出版を FAILED に戻す処理。 */
  private final StalePublications stalePublications;

  /* package */ FailedPublicationSweep(
      final DSLContext dsl,
      final FailedEventPublications failedEventPublications,
      final LockingTaskExecutor eventPublicationLockingTaskExecutor,
      final Clock clock,
      final EventPublicationResubmissionConfig.Properties properties,
      final StalePublications stalePublications) {
    this.dsl = dsl;
    this.failedEventPublications = failedEventPublications;
    this.lockingTaskExecutor = eventPublicationLockingTaskExecutor;
    this.clock = clock;
    this.properties = properties;
    this.stalePublications = stalePublications;
  }

  /**
   * 再投入した結果。
   *
   * @param advanced 実際に {@code RESUBMITTED} へ進んだ（または、すでに archive へ移った）出版の ID と、この再投入の後の {@code
   *     completion_attempts}
   * @param remaining 選ばれたが {@code max-per-run} を超えたため再投入しなかった数
   */
  /* package */ record Swept(Map<UUID, Integer> advanced, int remaining) {

    /* package */ Swept {
      advanced = Map.copyOf(advanced);
    }
  }

  /**
   * ロックを 1 回だけ試し、取れたら再投入する。他のインスタンスが持っていれば、何もせず空を返す。
   *
   * <p>ロックは、終わっても {@code lock-at-least-for}（実行の間隔の半分）の間は持ち続ける。
   *
   * <p>進んだ出版の読み戻しは、ロックを放してから行う。放すのに失敗したときは、結果を返さず例外で戻る。
   */
  /* package */ Optional<Swept> tryOnce(final Predicate<EventPublication> selection) {
    return attempt(selection, properties.lockAtLeastFor());
  }

  /**
   * ロックを取れるまで {@code lock-at-most-for} を上限に待ち、取れたら再投入する。下限は置かず、終わったらすぐ放す。
   *
   * <p>進んだ出版の読み戻しは、ロックを放してから行う。放すのに失敗したときは、結果を返さず例外で戻る。
   *
   * @throws IllegalStateException 上限の間にロックを取れなかったとき
   */
  /* package */ Swept waitUpTo(final Predicate<EventPublication> selection) {
    final int attempts = Polling.pollsWithin(properties.lockAtMostFor());
    Optional<Swept> swept = attempt(selection, Duration.ZERO);
    for (int attempt = 0; attempt < attempts && swept.isEmpty(); attempt++) {
      Polling.sleep();
      swept = attempt(selection, Duration.ZERO);
    }
    return swept.orElseThrow(() ->
        new IllegalStateException(
            "lock " + LOCK_NAME + " was not acquired within " + properties.lockAtMostFor()));
  }

  /**
   * ロックを 1 回試し、取れたら古い出版を FAILED に戻してから選んだ出版を再投入して、進んだ出版を返す。取れなければ空を返す。
   *
   * <p>FAILED に戻した出版は、同じ回の再投入の候補になる。
   *
   * <p>{@code FailedEventPublications.resubmit} は {@code LIMIT} を条件の関数より先に掛けるため（Spring Modulith
   * の挙動は {@code ResubmissionOptionsLearningTest} が固定する）、毎回 FAILED の行をすべて読む。{@code withMinAge} は
   * publication_date から測り、最後の試行からの待ち時間を表せないため呼ばない。
   */
  // ponytail: withBatchSize(Integer.MAX_VALUE) で全件をヒープへ読むため、メモリの上限は FAILED の出版の数に比例する。
  // 上限の回数目の再投入で失敗した出版は ABANDONED になるため、FAILED に溜まるのは待ち時間の中の出版と、最後の試行の途中で落ちた出版である。
  private Optional<Swept> attempt(
      final Predicate<EventPublication> selection, final Duration atLeastFor) {
    // 再投入はロックを取れた 1 回だけ走るので、この 3 つは 1 回の呼び出しの中でだけ使う。
    final Map<UUID, Integer> candidates = new ConcurrentHashMap<>();
    final AtomicInteger remaining = new AtomicInteger();
    final AtomicBoolean locked = new AtomicBoolean();
    final Predicate<EventPublication> limited =
        publication -> {
          if (!selection.test(publication)) {
            return false;
          }
          if (candidates.size() >= properties.maxPerRun()) {
            remaining.incrementAndGet();
            return false;
          }
          candidates.put(publication.getIdentifier(), publication.getCompletionAttempts());
          return true;
        };
    lockingTaskExecutor.executeWithLock(
        (Runnable)
            () -> {
              locked.set(true);
              stalePublications.markFailed();
              failedEventPublications.resubmit(
                  ResubmissionOptions.defaults()
                      .withBatchSize(Integer.MAX_VALUE)
                      .withFilter(limited));
            },
        new LockConfiguration(
            Instant.now(clock), LOCK_NAME, properties.lockAtMostFor(), atLeastFor));
    return locked.get()
        ? Optional.of(new Swept(advancedAmong(candidates), remaining.get()))
        : Optional.empty();
  }

  /** 候補のうち、実際に {@code RESUBMITTED} へ進んだ出版の ID と、この再投入の後の {@code completion_attempts} を返す。 */
  private Map<UUID, Integer> advancedAmong(final Map<UUID, Integer> candidates) {
    final Map<UUID, EventPublicationStates.State> states =
        EventPublicationStates.read(dsl, candidates.keySet());
    // markResubmitted は completion_attempts をちょうど 1 増やすため、進んだ後の値は候補の値 + 1 になる。
    return candidates.entrySet().stream()
        .filter(candidate -> {
          final EventPublicationStates.@Nullable State state = states.get(candidate.getKey());
          return state != null
              && (state.isArchived() || state.completionAttempts() > candidate.getValue());
        })
        .collect(
            Collectors.toUnmodifiableMap(Map.Entry::getKey, candidate -> candidate.getValue() + 1));
  }
}
