package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;

/**
 * 定期のジョブが再投入した出版を、公開された表の列だけで読み戻す（ADR-077）。
 *
 * <p>Spring Modulith 2.1.1 の {@code FailedEventPublications.resubmit} は件数を返さず、成功と再失敗を知らせる hook もない。
 */
final class ResubmissionTracker {

  /** Spring Boot が構成した jOOQ。 */
  private final DSLContext dsl;

  /* package */ ResubmissionTracker(final DSLContext dsl) {
    this.dsl = dsl;
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
