package com.example.demo.shared.eventresubmission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.EventPublication.Status;

/**
 * 定期のジョブが再投入した出版を覚え、次の回以降に結果を DB から読み戻して数える（ADR-079）。
 *
 * <p>Spring Modulith 2.2.0 の {@code FailedEventPublications.resubmit} は件数を返さず、成功を知らせる hook もない。
 * そのため、公開された表（{@code event_publication} と {@code event_publication_archive}）の列だけで判定する。
 *
 * <p>覚えるのは、このインスタンスが {@code RESUBMITTED} へ進めた出版の ID だけである。 結果の Counter
 * は再投入したインスタンスだけが数えるため、インスタンスの間で二重に数えず、集計は {@code sum} で行う。
 */
// ponytail: 覚えた ID はインスタンスのメモリにあり、インスタンスが再投入の直後に落ちると、その回の結果は数えない。
// Gauge（event.publication.failed）と最古の経過時間の警報が補う。完了と諦めは archive の行の status で判定するため
// completion-mode: archive が前提である。
// 結果の判定は次の回まで遅れる。確実に数える必要が出たら、結果を持つ表を足して全インスタンスで共有する。
@Slf4j
final class ResubmissionTracker {

  /** 結果の Counter の名前。 */
  private static final String OUTCOMES_METRIC = "event.publication.resubmission.outcomes";

  /** 再投入のあとに完了した出版の数。 */
  private final Counter completed;

  /** 再投入のあとに再び失敗した出版の数。回数の上限を超えて ABANDONED になった出版を含む。 */
  private final Counter failed;

  /** 再投入の回数の上限を超えて ABANDONED になった出版の数。 */
  private final Counter exhausted;

  /** Spring Boot が構成した jOOQ。 */
  private final DSLContext dsl;

  /** 覚えておく期間。未完了の状態の ID だけに適用する。 */
  private final Duration lifetime;

  /** 再投入した出版の ID と、その再投入。 */
  private final Map<UUID, Resubmission> inFlight = new ConcurrentHashMap<>();

  /**
   * このインスタンスの 1 回の再投入。
   *
   * @param at 再投入した回の時刻
   * @param attempts 再投入の後の {@code completion_attempts}。後の再投入だけがこれを増やす
   */
  private record Resubmission(Instant at, int attempts) {}

  /* package */ ResubmissionTracker(
      final DSLContext dsl,
      final MeterRegistry meterRegistry,
      final EventPublicationResubmissionConfig.Properties properties) {
    this.dsl = dsl;
    this.lifetime = lifetimeOf(properties);
    this.completed = outcome(meterRegistry, "completed");
    this.failed = outcome(meterRegistry, "failed");
    this.exhausted =
        Counter.builder("event.publication.resubmission.exhausted")
            .description("再投入の回数の上限を超えて失敗し、ABANDONED になった出版の数")
            .register(meterRegistry);
  }

  private static Counter outcome(final MeterRegistry meterRegistry, final String outcome) {
    return Counter.builder(OUTCOMES_METRIC)
        .tag("outcome", outcome)
        .description("再投入した出版の結果。completed は完了、failed は再び失敗するか ABANDONED になった数")
        .register(meterRegistry);
  }

  /**
   * 覚えておく期間。{@code max(3 × lock-at-most-for, in-flight-staleness + lock-at-most-for + 2 ×
   * interval)}。
   *
   * <p>落ちたまま残った再投入の出版の last_resubmission_date は、この回の時刻から最大で {@code lock-at-most-for} 遅れる。
   * 定期のジョブはロックを取った回に、そこから {@code in-flight-staleness} を過ぎた出版を FAILED に戻し、ロックはどれかのインスタンスがおよそ {@code
   * interval} ごとに取る。 次の回はさらに最大で {@code interval} 後に数える。 その後の回で数えるまでの余裕として、ロックの上限の 3 倍とも比べて長い方を取る。
   */
  /* package */ static Duration lifetimeOf(
      final EventPublicationResubmissionConfig.Properties properties) {
    final Duration byLock = properties.lockAtMostFor().multipliedBy(3);
    final Duration byStaleness =
        properties
            .inFlightStaleness()
            .plus(properties.lockAtMostFor())
            .plus(properties.interval().multipliedBy(2));
    return byLock.compareTo(byStaleness) > 0 ? byLock : byStaleness;
  }

  /**
   * {@code RESUBMITTED} へ進んだ出版を、再投入の後の {@code completion_attempts} と覚える。
   *
   * <p>覚えている出版をまた再投入したら、前の再投入は完了しなかったので {@code failed} に数える。 処理中のまま残った出版を、古い出版を FAILED
   * に戻す処理が戻し、同じ回に再投入したときである。
   */
  /* package */ void remember(final Map<UUID, Integer> advanced, final Instant now) {
    advanced.forEach((id, attempts) -> {
      if (inFlight.put(id, new Resubmission(now, attempts)) != null) {
        failed.increment();
      }
    });
  }

  /**
   * 覚えている出版の結果を DB から読み戻して数える。ロックの外ですべてのインスタンスが、自分が覚えている ID だけについて行う。
   *
   * <p>{@code completion_attempts} が覚えた値より増えた出版は、別のインスタンスか手での 1 回の再投入が再投入し直したので、再失敗として数えて忘れる。
   * それ以外では、{@code COMPLETED} は完了、{@code FAILED} と {@code ABANDONED} は再失敗として数えて忘れる。{@code ABANDONED}
   * は ERROR でも知らせる。 処理中の状態（{@link
   * EventPublicationStates.State#isInFlight()}）は次の回に回す。どちらの表にもない出版は、運用者が消したので数えずに忘れる。 未完了の状態のまま
   * {@code lifetime} を過ぎた ID は数えずに捨てる。
   */
  /* package */ void reconcile(final Instant now) {
    if (inFlight.isEmpty()) {
      return;
    }
    final Map<UUID, EventPublicationStates.State> states =
        EventPublicationStates.read(dsl, inFlight.keySet());
    inFlight.forEach((id, resubmission) -> {
      final EventPublicationStates.@Nullable State state = states.get(id);
      if (state == null) {
        inFlight.remove(id);
      } else if (state.completionAttempts() > resubmission.attempts()) {
        conclude(id, failed);
      } else if (state.isInFlight()) {
        forgetIfExpired(id, resubmission.at(), now);
      } else if (state.status() == Status.COMPLETED) {
        conclude(id, completed);
      } else {
        conclude(id, failed);
        if (state.status() == Status.ABANDONED) {
          exhausted.increment();
          log.atError()
              .addKeyValue("event_publication.id", id.toString())
              .addKeyValue("event_publication.event_type", state.eventType())
              .addKeyValue("event_publication.listener_id", state.listenerId())
              .addKeyValue("event_publication.completion_attempts", state.completionAttempts())
              .log("Event publication resubmission exhausted");
        }
      }
    });
  }

  private void conclude(final UUID id, final Counter outcome) {
    inFlight.remove(id);
    outcome.increment();
  }

  private void forgetIfExpired(final UUID id, final Instant resubmittedAt, final Instant now) {
    if (Duration.between(resubmittedAt, now).compareTo(lifetime) > 0) {
      inFlight.remove(id);
    }
  }
}
