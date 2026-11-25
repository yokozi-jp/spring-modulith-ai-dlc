package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.eventresubmission.ResubmissionProbes.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.CompletedEventPublications;

/**
 * archive の行の保持期限の削除が、完了の時刻から 7 日を過ぎた {@code COMPLETED} の行だけを消し、期限ちょうどの行、{@code ABANDONED}
 * の行、未完了の表の行を残すことを、時刻を固定して本物の DB で確かめる（ADR-120）。
 */
@ResubmissionTestSupport.SharedContext
class EventPublicationArchiveRetentionTest {

  /** 削除を動かす時刻。マイクロ秒の精度にする。 */
  private static final Instant NOW = Instant.parse("2026-10-17T01:02:03.123456Z");

  /** application.yaml の保持期限。 */
  private static final Duration RETENTION = Duration.ofDays(7);

  /** 削除の時刻から保持期限ちょうど前の時刻。 */
  private static final Instant EXPIRY = NOW.minus(RETENTION);

  /** 出版の行を作り、残った行を読む。 */
  @Autowired private DSLContext dsl;

  /** Spring Modulith のレジストリ。保持期限の基準の時刻は {@link #clock} から読む。 */
  @Autowired private CompletedEventPublications completed;

  /** 差し替えられる時計。 */
  @Autowired private MutableClock clock;

  /** 読み戻す期間を決める再投入の設定値。 */
  @Autowired private EventPublicationResubmissionConfig.Properties resubmission;

  @Test
  @DisplayName("完了の時刻から 7 日を 1 マイクロ秒過ぎた COMPLETED の行だけを消し、7 日ちょうどの行、ABANDONED の行、未完了の行を残す")
  void deletesOnlyCompletedRowsOlderThanRetention() {
    final UUID expired =
        ResubmissionTestSupport.insertArchived(dsl, EXPIRY.minusNanos(1_000), "COMPLETED");
    final UUID boundary = ResubmissionTestSupport.insertArchived(dsl, EXPIRY, "COMPLETED");
    final UUID abandoned =
        ResubmissionTestSupport.insertArchived(dsl, EXPIRY.minus(Duration.ofDays(30)), "ABANDONED");
    final UUID live =
        ResubmissionTestSupport.insertPublication(
            dsl,
            UUID.randomUUID(),
            "archive-retention-test",
            EXPIRY.minus(Duration.ofDays(30)),
            "FAILED",
            1);
    clock.set(NOW);

    new EventPublicationArchiveRetentionConfig.Scheduling(
            completed,
            new EventPublicationArchiveRetentionConfig.Properties(RETENTION, Duration.ofDays(1)),
            resubmission)
        .deleteExpired();

    assertThat(
            dsl.select(EVENT_PUBLICATION_ARCHIVE.ID)
                .from(EVENT_PUBLICATION_ARCHIVE)
                .where(EVENT_PUBLICATION_ARCHIVE.ID.in(expired, boundary, abandoned))
                .fetch(EVENT_PUBLICATION_ARCHIVE.ID))
        .as("archive に残った行")
        .containsExactlyInAnyOrder(boundary, abandoned);
    assertThat(dsl.fetchExists(EVENT_PUBLICATION, EVENT_PUBLICATION.ID.eq(live)))
        .as("未完了の表の行")
        .isTrue();
  }
}
