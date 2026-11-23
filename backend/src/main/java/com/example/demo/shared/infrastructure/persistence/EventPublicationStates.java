package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.EventPublication;

/**
 * 出版の ID ごとの今の状態を、未完了の表（{@code event_publication}）と archive の表から読む（ADR-079）。
 *
 * <p>{@code completion-mode: archive} では、完了した出版と諦めた出版は archive の表へ移る。 2 つの表を 1 つの文で読むため、同じ時点の状態になる。
 * Spring Modulith と Runbook の SQL は行を 1 つのトランザクションで移すため、同じ ID が両方の表に見えることはない。 どちらの表にもない
 * ID（運用者が消した）は結果に含めない。
 */
final class EventPublicationStates {

  private EventPublicationStates() {}

  /** ID ごとの今の状態。 */
  /* package */ static Map<UUID, State> read(final DSLContext dsl, final Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    final UUID[] keys = ids.toArray(UUID[]::new);
    return dsl.select(
            EVENT_PUBLICATION.ID,
            EVENT_PUBLICATION.STATUS,
            EVENT_PUBLICATION.LISTENER_ID,
            EVENT_PUBLICATION.EVENT_TYPE,
            EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
        .from(EVENT_PUBLICATION)
        .where(EVENT_PUBLICATION.ID.eq(DSL.any(keys)))
        .unionAll(
            dsl.select(
                    EVENT_PUBLICATION_ARCHIVE.ID,
                    EVENT_PUBLICATION_ARCHIVE.STATUS,
                    EVENT_PUBLICATION_ARCHIVE.LISTENER_ID,
                    EVENT_PUBLICATION_ARCHIVE.EVENT_TYPE,
                    EVENT_PUBLICATION_ARCHIVE.COMPLETION_ATTEMPTS)
                .from(EVENT_PUBLICATION_ARCHIVE)
                .where(EVENT_PUBLICATION_ARCHIVE.ID.eq(DSL.any(keys))))
        .fetchMap(
            row -> row.value1(),
            row -> State.of(row.value2(), row.value3(), row.value4(), row.value5()));
  }

  /**
   * 出版の 1 行の状態。
   *
   * @param status 状態。Spring Modulith と同じく、列が空なら {@code null}
   * @param listenerId リスナーの ID
   * @param eventType イベントの完全修飾名
   * @param completionAttempts 試行の回数（再投入した回数 + 1）。Spring Modulith と同じく、列が空なら 0
   */
  /* package */ record State(
      EventPublication.@Nullable Status status,
      String listenerId,
      String eventType,
      int completionAttempts) {

    private static State of(
        final @Nullable String status,
        final String listenerId,
        final String eventType,
        final @Nullable Integer completionAttempts) {
      return new State(
          status == null ? null : EventPublication.Status.valueOf(status),
          listenerId,
          eventType,
          Objects.requireNonNullElse(completionAttempts, 0));
    }
  }
}
