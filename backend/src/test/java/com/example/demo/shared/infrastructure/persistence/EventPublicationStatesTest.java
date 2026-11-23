package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication;

/** {@link EventPublicationStates} が、未完了の表と archive の表から出版の今の状態を読むことを検証する。 */
@ResubmissionTestSupport.SharedContext
class EventPublicationStatesTest {

  /** 出版の時刻。マイクロ秒の精度にする。 */
  private static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** 未完了の行のリスナーの ID。 */
  private static final String LISTENER_ID = "states-test";

  /** 出版の行を作る DB。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("未完了の行と archive の行の状態を読み、どちらにもない ID を含めず、空の状態と回数を null と 0 で返す")
  void readsLiveAndArchivedRows() {
    final UUID failed =
        ResubmissionTestSupport.insertPublication(
            dsl, UUID.randomUUID(), LISTENER_ID, T0, "FAILED", 2);
    final UUID legacy =
        ResubmissionTestSupport.insertPublication(
            dsl, UUID.randomUUID(), LISTENER_ID, T0, "PUBLISHED", 1);
    dsl.update(EVENT_PUBLICATION)
        .setNull(EVENT_PUBLICATION.STATUS)
        .setNull(EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
        .where(EVENT_PUBLICATION.ID.eq(legacy))
        .execute();
    final UUID abandoned = ResubmissionTestSupport.insertArchived(dsl, T0, "ABANDONED");
    final UUID deleted = UUID.randomUUID();

    assertThat(EventPublicationStates.read(dsl, List.of(failed, legacy, abandoned, deleted)))
        .containsOnly(
            entry(failed, EventPublication.Status.FAILED, LISTENER_ID, 2),
            entry(legacy, null, LISTENER_ID, 0),
            entry(abandoned, EventPublication.Status.ABANDONED, "archived-test", 1));
    assertThat(EventPublicationStates.read(dsl, List.of())).isEmpty();
  }

  private static Map.Entry<UUID, EventPublicationStates.State> entry(
      final UUID id,
      final EventPublication.@Nullable Status status,
      final String listenerId,
      final int completionAttempts) {
    return Map.entry(
        id, new EventPublicationStates.State(status, listenerId, "test.event", completionAttempts));
  }
}
