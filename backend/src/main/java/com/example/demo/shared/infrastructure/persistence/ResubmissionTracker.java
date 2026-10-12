package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record5;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;

/**
 * 定期のジョブが再投入した出版を覚え、次の回以降に結果を DB から読み戻して数える（ADR-079）。
 *
 * <p>Spring Modulith 2.1.1 の {@code FailedEventPublications.resubmit} は件数を返さず、成功と再失敗を知らせる hook もない。
 * そのため、公開された表（{@code event_publication} と {@code event_publication_archive}）の列だけで判定する。
 *
 * <p>覚えるのは、このインスタンスが {@code RESUBMITTED} へ進めた出版の ID だけである。 結果の Counter
 * は再投入したインスタンスだけが数えるため、インスタンスの間で二重に数えず、集計は {@code sum} で行う。
 */
// ponytail: 覚えた ID はインスタンスのメモリにあり、インスタンスが再投入の直後に落ちると、その回の結果は数えない。
// Gauge（event.publication.failed）と最古の経過時間の警報が補う。完了は archive の行で判定するため completion-mode: archive
// が前提である。
// 結果の判定は次の回まで遅れる。確実に数える必要が出たら、結果を持つ表を足して全インスタンスで共有する。
@Slf4j
final class ResubmissionTracker {

  /** Spring Modulith の失敗した出版の状態。 */
  private static final String FAILED = "FAILED";

  /** 結果の Counter の名前。 */
  private static final String OUTCOMES_METRIC = "event.publication.resubmission.outcomes";

  /** 再投入のあとに完了した出版の数。 */
  private final Counter completed;

  /** 再投入のあとに再び失敗した出版の数。 */
  private final Counter failed;

  /** 再投入のあとの失敗で、方針の上限に達した出版の数。 */
  private final Counter exhausted;

  /** Spring Boot が構成した jOOQ。 */
  private final DSLContext dsl;

  /** リスナーの再投入の方針。 */
  private final ResubmissionPolicies policies;

  /** 覚えておく期間。未完了の状態の ID だけに適用する。 */
  private final Duration lifetime;

  /** 再投入した出版の ID と、再投入した時刻。 */
  private final Map<UUID, Instant> inFlight = new ConcurrentHashMap<>();

  /** このインスタンスで ERROR を記録した、上限に達した出版の ID。 */
  private final Set<UUID> reported = ConcurrentHashMap.newKeySet();

  /* package */ ResubmissionTracker(
      final DSLContext dsl,
      final ResubmissionPolicies policies,
      final MeterRegistry meterRegistry,
      final EventPublicationResubmissionConfig.Properties properties) {
    this.dsl = dsl;
    this.policies = policies;
    this.lifetime = lifetimeOf(properties);
    this.completed = outcome(meterRegistry, "completed");
    this.failed = outcome(meterRegistry, "failed");
    this.exhausted =
        Counter.builder("event.publication.resubmission.exhausted")
            .description("再投入のあとの失敗で、方針の再投入の回数の上限に達した出版の数")
            .register(meterRegistry);
  }

  private static Counter outcome(final MeterRegistry meterRegistry, final String outcome) {
    return Counter.builder(OUTCOMES_METRIC)
        .tag("outcome", outcome)
        .description("再投入した出版の結果。completed は完了、failed は再び失敗した数")
        .register(meterRegistry);
  }

  /**
   * 覚えておく期間。{@code max(3 × lock-at-most-for, wait-age + interval)}。
   *
   * <p>再投入した出版は Staleness Monitor が {@code wait-age} を過ぎたら FAILED に戻し、その検出は {@code interval} ごとに動く。
   * その後の回で数えるまでの余裕として、ロックの上限の 3 倍とも比べて長い方を取る。
   */
  /* package */ static Duration lifetimeOf(
      final EventPublicationResubmissionConfig.Properties properties) {
    final Duration byLock = properties.lockAtMostFor().multipliedBy(3);
    final Duration byStaleness = properties.waitAge().plus(properties.interval());
    return byLock.compareTo(byStaleness) > 0 ? byLock : byStaleness;
  }

  /** このインスタンスで ERROR を記録した出版の ID。 */
  /* package */ Set<UUID> reportedIds() {
    return reported;
  }

  /** 上限に達した出版を、まだ記録していなければ ERROR で記録する。新しく記録したら true を返す。 */
  /* package */ boolean report(
      final UUID id, final String eventType, final String listenerId, final int attempts) {
    if (!reported.add(id)) {
      return false;
    }
    log.atError()
        .addKeyValue("event_publication.id", id.toString())
        .addKeyValue("event_publication.event_type", eventType)
        .addKeyValue("event_publication.listener_id", listenerId)
        .addKeyValue("event_publication.completion_attempts", attempts)
        .log("Event publication resubmission exhausted");
    return true;
  }

  /** 候補のうち実際に {@code RESUBMITTED} へ進んだ出版を覚え、その数を返す。 */
  /* package */ int track(final Map<UUID, Integer> candidates, final Instant now) {
    final Set<UUID> advanced = advancedAmong(candidates);
    advanced.forEach(id -> inFlight.put(id, now));
    return advanced.size();
  }

  /**
   * 覚えている出版の結果を DB から読み戻して数える。ロックの外ですべてのインスタンスが、自分が覚えている ID だけについて行う。
   *
   * <p>archive にある出版は完了、{@code FAILED} は再失敗として数えて忘れる（上限を超えていれば exhausted も数え、ERROR を 1 回記録する）。
   * {@code RESUBMITTED}、{@code PROCESSING}、{@code PUBLISHED} は次の回に回す。どちらの表にもない出版は、運用者が消したので数えずに忘れる。
   * 未完了の状態のまま {@code lifetime} を過ぎた ID は数えずに捨てる。
   */
  /* package */ void reconcile(final Instant now) {
    if (inFlight.isEmpty()) {
      return;
    }
    final Map<UUID, Record5<UUID, String, String, String, Integer>> live =
        dsl.select(
                EVENT_PUBLICATION.ID,
                EVENT_PUBLICATION.EVENT_TYPE,
                EVENT_PUBLICATION.LISTENER_ID,
                EVENT_PUBLICATION.STATUS,
                EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.ID.eq(DSL.any(inFlight.keySet().toArray(UUID[]::new))))
            .fetchMap(EVENT_PUBLICATION.ID);
    final Set<UUID> missing = new HashSet<>(inFlight.keySet());
    missing.removeAll(live.keySet());
    final Set<UUID> archived = archivedAmong(missing);
    for (final UUID id : missing) {
      if (archived.contains(id)) {
        completed.increment();
      }
      inFlight.remove(id);
    }
    live.values().forEach(row -> reconcileLive(row, now));
  }

  private void reconcileLive(
      final Record5<UUID, String, String, String, Integer> row, final Instant now) {
    final UUID id = row.value1();
    if (FAILED.equals(row.value4())) {
      inFlight.remove(id);
      failed.increment();
      final int attempts = Objects.requireNonNullElse(row.value5(), 0);
      if (attempts > policies.policyOf(row.value3()).maxResubmissions()) {
        exhausted.increment();
        report(id, row.value2(), row.value3(), attempts);
      }
    } else if (Duration.between(inFlight.get(id), now).compareTo(lifetime) > 0) {
      inFlight.remove(id);
    }
  }

  /**
   * 候補のうち、実際に {@code RESUBMITTED} へ進んだ出版の ID を返す。
   *
   * <p>{@code FailedEventPublications.resubmit} は件数を返さず、{@code markResubmitted} は他の更新で偽になりうる（{@code
   * STATUS != 'RESUBMITTED'} の行だけを更新する）。 そのため、再投入のあとに読み戻し、{@code completion_attempts}
   * が増えた行と、すでに完了して archive に移った行を「進んだ」とする。 進んだ行は {@code markResubmitted} が必ず {@code
   * completion_attempts} を 1 増やす。
   */
  /* package */ Set<UUID> advancedAmong(final Map<UUID, Integer> candidates) {
    if (candidates.isEmpty()) {
      return Set.of();
    }
    final Map<UUID, Integer> attempts =
        dsl.select(EVENT_PUBLICATION.ID, EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.ID.eq(DSL.any(candidates.keySet().toArray(UUID[]::new))))
            .fetchMap(EVENT_PUBLICATION.ID, EVENT_PUBLICATION.COMPLETION_ATTEMPTS);
    final Set<UUID> advanced = new HashSet<>();
    final Set<UUID> missing = new HashSet<>();
    candidates.forEach(
        (id, before) -> {
          final @Nullable Integer after = attempts.get(id);
          if (after == null) {
            missing.add(id);
          } else if (after > before) {
            advanced.add(id);
          }
        });
    advanced.addAll(archivedAmong(missing));
    return advanced;
  }

  /** ID のうち、完了して archive に移ったもの。 */
  private Set<UUID> archivedAmong(final Set<UUID> ids) {
    if (ids.isEmpty()) {
      return Set.of();
    }
    return dsl.select(EVENT_PUBLICATION_ARCHIVE.ID)
        .from(EVENT_PUBLICATION_ARCHIVE)
        .where(EVENT_PUBLICATION_ARCHIVE.ID.eq(DSL.any(ids.toArray(UUID[]::new))))
        .fetchSet(EVENT_PUBLICATION_ARCHIVE.ID);
  }
}
