package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;

import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.modulith.events.EventPublication.Status;
import org.springframework.stereotype.Component;

/**
 * 落ちたまま残った出版を {@code FAILED} に戻す（ADR-079）。
 *
 * <p>Spring Modulith の {@code EventPublicationRegistry.markStalePublicationsFailed} は、古い出版を読んでから 1
 * 行ずつ {@code markFailed(id)} を実行し、更新で状態も {@code last_resubmission_date} も確かめない。回が {@code
 * lock-at-most-for} を超えて止まりロックが切れると、別のインスタンスが再投入した処理中の出版を {@code FAILED} に戻しうる。
 * ここは、完了していないことと状態ごとの古さを 1 文の UPDATE で確かめ、読んでから更新するまでの隙間を作らない。
 *
 * <p>古さは Spring Modulith と同じく、最後の再投入の時刻（{@code last_resubmission_date}、なければ {@code
 * publication_date}）から測る。閾値は {@code PUBLISHED} が {@code wait-age}、{@code PROCESSING} と {@code
 * RESUBMITTED} が {@code in-flight-staleness} である。 {@code FAILED} と終端の状態は更新しない。
 */
@Slf4j
@Component
final class StalePublications {

  /** Spring Boot が構成した jOOQ。 */
  private final DSLContext dsl;

  /** 現在時刻。 */
  private final Clock clock;

  /** 設定値。 */
  private final EventPublicationResubmissionConfig.Properties properties;

  /* package */ StalePublications(
      final DSLContext dsl,
      final Clock clock,
      final EventPublicationResubmissionConfig.Properties properties) {
    this.dsl = dsl;
    this.clock = clock;
    this.properties = properties;
  }

  /** 古い出版を 1 文で {@code FAILED} にする。 */
  /* package */ void markFailed() {
    final Instant now = Instant.now(clock);
    final Field<Instant> lastAttempt =
        DSL.coalesce(EVENT_PUBLICATION.LAST_RESUBMISSION_DATE, EVENT_PUBLICATION.PUBLICATION_DATE);
    final Condition stale =
        EVENT_PUBLICATION
            .STATUS
            .eq(Status.PUBLISHED.name())
            .and(lastAttempt.lt(now.minus(properties.waitAge())))
            .or(
                EVENT_PUBLICATION
                    .STATUS
                    .in(Status.PROCESSING.name(), Status.RESUBMITTED.name())
                    .and(lastAttempt.lt(now.minus(properties.inFlightStaleness()))));
    final int marked =
        dsl.update(EVENT_PUBLICATION)
            .set(EVENT_PUBLICATION.STATUS, Status.FAILED.name())
            .where(EVENT_PUBLICATION.COMPLETION_DATE.isNull().and(stale))
            .execute();
    if (marked > 0) {
      log.info("Stale event publications marked failed");
    }
  }
}
