package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.eventresubmission.ResubmissionProbes.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication.Status;

/**
 * {@link StalePublications} が、完了していない {@code PUBLISHED}、{@code PROCESSING}、{@code RESUBMITTED}
 * の出版だけを、状態ごとの閾値を過ぎたときに {@code FAILED} にすることと、更新の時点で状態と時刻を確かめ直すことを、本物の DB で確かめる。
 *
 * <p>閾値は設定の {@code wait-age} と {@code in-flight-staleness} である。時間は {@link MutableClock} で動かす。
 */
@ResubmissionTestSupport.SharedContext
class StalePublicationsTest {

  /** 最初の出版の時刻。マイクロ秒の精度にする。 */
  private static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** このテストの行のリスナーの ID。後始末で消す。 */
  private static final String LISTENER_ID = "stale-publications-test";

  /** 検証対象。 */
  @Autowired private StalePublications stalePublications;

  /** 差し替えられる時計。 */
  @Autowired private MutableClock clock;

  /** 閾値を持つ設定値。 */
  @Autowired private EventPublicationResubmissionConfig.Properties properties;

  /** 別のトランザクションの行ロックを作る接続の元。 */
  @Autowired private DataSource dataSource;

  /** 出版の行を作り、状態を読む。 */
  @Autowired private DSLContext dsl;

  @BeforeEach
  void reset() {
    clock.set(T0);
  }

  /** 完了していない行を残すと、後のテストの掃除が処理中の出版の完了を待ち続ける。 */
  @AfterEach
  void deleteRows() {
    dsl.deleteFrom(EVENT_PUBLICATION)
        .where(EVENT_PUBLICATION.LISTENER_ID.eq(LISTENER_ID))
        .execute();
  }

  @ParameterizedTest
  @EnumSource(
      value = Status.class,
      names = {"PUBLISHED", "PROCESSING", "RESUBMITTED"})
  @DisplayName("出版の時刻から状態ごとの閾値ちょうどの出版は残し、1 マイクロ秒過ぎた出版は FAILED にする")
  void marksFailedOnlyAfterTheThresholdOfTheStatus(final Status status) {
    final Duration threshold = thresholdOf(status);
    final UUID publication = insert(status, null);

    clock.set(T0.plus(threshold));
    stalePublications.markFailed();

    assertThat(status(publication)).as("ちょうど閾値の %s", status).isEqualTo(status.name());

    clock.set(T0.plus(threshold).plus(1, ChronoUnit.MICROS));
    stalePublications.markFailed();

    assertThat(status(publication)).as("閾値を過ぎた %s", status).isEqualTo(Status.FAILED.name());
  }

  @ParameterizedTest
  @EnumSource(
      value = Status.class,
      names = {"PUBLISHED", "PROCESSING", "RESUBMITTED"})
  @DisplayName("再投入した出版は、出版の時刻が古くても、最後の再投入の時刻から閾値を過ぎるまで FAILED にしない")
  void measuresFromTheLastResubmission(final Status status) {
    final Duration threshold = thresholdOf(status);
    final Instant resubmittedAt = T0.plus(Duration.ofHours(1));
    final UUID publication = insert(status, resubmittedAt);

    clock.set(resubmittedAt.plus(threshold));
    stalePublications.markFailed();

    assertThat(status(publication)).as("再投入から閾値ちょうどの %s", status).isEqualTo(status.name());

    clock.set(resubmittedAt.plus(threshold).plus(1, ChronoUnit.MICROS));
    stalePublications.markFailed();

    assertThat(status(publication)).as("再投入から閾値を過ぎた %s", status).isEqualTo(Status.FAILED.name());
  }

  @Test
  @DisplayName("FAILED、完了の日時がある出版、終端の状態の行は、どれほど古くても変えない")
  void leavesFailedCompletedAndTerminalRowsAlone() {
    final UUID failed = insert(Status.FAILED, null);
    final UUID completedProcessing = insert(Status.PROCESSING, null);
    dsl.update(EVENT_PUBLICATION)
        .set(EVENT_PUBLICATION.COMPLETION_DATE, T0)
        .where(EVENT_PUBLICATION.ID.eq(completedProcessing))
        .execute();
    final UUID completed = insert(Status.COMPLETED, null);
    final UUID abandoned = insert(Status.ABANDONED, null);

    clock.set(T0.plus(Duration.ofDays(365)));
    stalePublications.markFailed();

    assertThat(
            List.of(
                status(failed), status(completedProcessing), status(completed), status(abandoned)))
        .containsExactly("FAILED", "PROCESSING", "COMPLETED", "ABANDONED");
  }

  @Test
  @DisplayName("古い行を読んだ後に別のトランザクションが再投入して確定した出版は、更新の時点で確かめ直して FAILED にしない")
  void doesNotMarkPublicationResubmittedWhileTheUpdateWaitsForTheRowLock() throws Exception {
    final UUID publication = insert(Status.PUBLISHED, null);
    final Instant now = T0.plus(properties.waitAge()).plus(1, ChronoUnit.MICROS);
    clock.set(now);

    try (var other = dataSource.getConnection()) {
      other.setAutoCommit(false);
      DSL.using(other, SQLDialect.POSTGRES)
          .update(EVENT_PUBLICATION)
          .set(EVENT_PUBLICATION.STATUS, Status.RESUBMITTED.name())
          .set(EVENT_PUBLICATION.LAST_RESUBMISSION_DATE, now)
          .where(EVENT_PUBLICATION.ID.eq(publication))
          .execute();
      final CompletableFuture<Void> marking =
          ResubmissionTestSupport.onVirtualThread(() -> {
            stalePublications.markFailed();
            return null;
          });
      Awaitility.await("markFailed が行ロックを待つ")
          .atMost(Duration.ofSeconds(ResubmissionProbes.JOIN_TIMEOUT_SECONDS))
          .until(this::updateIsWaitingForRowLock);
      other.commit();
      marking.get(ResubmissionProbes.JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    assertThat(status(publication)).as("再投入が確定した後の状態").isEqualTo(Status.RESUBMITTED.name());
  }

  private boolean updateIsWaitingForRowLock() {
    return dsl.fetchExists(
        DSL.table("pg_stat_activity"),
        DSL.field("wait_event_type")
            .eq("Lock")
            .and(DSL.field("query").like("update \"modulith\".\"event_publication\"%")));
  }

  private Duration thresholdOf(final Status status) {
    return status == Status.PUBLISHED ? properties.waitAge() : properties.inFlightStaleness();
  }

  private UUID insert(final Status status, final @Nullable Instant lastResubmission) {
    final UUID id =
        ResubmissionTestSupport.insertPublication(
            dsl, UUID.randomUUID(), LISTENER_ID, T0, status.name(), 1);
    if (lastResubmission != null) {
      dsl.update(EVENT_PUBLICATION)
          .set(EVENT_PUBLICATION.LAST_RESUBMISSION_DATE, lastResubmission)
          .where(EVENT_PUBLICATION.ID.eq(id))
          .execute();
    }
    return id;
  }

  private @Nullable String status(final UUID publication) {
    return dsl.select(EVENT_PUBLICATION.STATUS)
        .from(EVENT_PUBLICATION)
        .where(EVENT_PUBLICATION.ID.eq(publication))
        .fetchSingle(EVENT_PUBLICATION.STATUS);
  }
}
