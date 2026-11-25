package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.eventresubmission.ResubmissionProbes.MutableClock;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.OnceProbe;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.OnceProbeListener;
import com.example.demo.testkit.AsTenant;
import java.time.Duration;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;

/**
 * 方針の {@code abandon-after-attempts: 1} のリスナーは、最初の配信の失敗で出版が {@code ABANDONED}
 * になり、定期の再投入が呼ばないことを検証する（ADR-079）。
 *
 * <p>全体の {@code abandon-after-attempts} は 2 なので、方針がなければ最初の失敗は {@code FAILED} に残り再投入される。
 */
@ResubmissionTestSupport.SharedContext
@EnableScenarios
class EventPublicationResubmitterAbandonPolicyTest {

  /** 検証対象の定期のジョブ。 */
  @Autowired private EventPublicationResubmitter resubmitter;

  /** 失敗の回数を数えるテストのリスナー。 */
  @Autowired private OnceProbeListener listener;

  /** 出版と archive の行を読む。 */
  @Autowired private DSLContext dsl;

  /** 出版の時刻を決める時計。 */
  @Autowired private MutableClock clock;

  @Test
  @DisplayName("最初の配信が失敗した出版は ABANDONED で archive に移り、待ち時間を過ぎても定期の再投入はリスナーを呼ばない")
  void firstFailureAbandonsAndIsNotResubmitted(final Scenario scenario) {
    final String id = UUID.randomUUID().toString();

    scenario
        .publish(new OnceProbe(id, AsTenant.TENANT))
        .andWaitForStateChange(
            () -> archivedStatus(id), EventPublication.Status.ABANDONED.name()::equals)
        .andVerify(done -> {});

    assertThat(dsl.fetchCount(EVENT_PUBLICATION, EVENT_PUBLICATION.SERIALIZED_EVENT.contains(id)))
        .as("id=%s は未完了の表に残らない", id)
        .isZero();
    assertThat(listener.calls()).as("最初の配信だけ").isEqualTo(1);

    clock.set(clock.instant().plus(Duration.ofDays(1)));
    ResubmissionTestSupport.runJob(dsl, resubmitter);

    assertThat(listener.calls()).as("待ち時間を過ぎても再投入しない").isEqualTo(1);
    assertThat(archivedStatus(id)).isEqualTo(EventPublication.Status.ABANDONED.name());
  }

  private String archivedStatus(final String id) {
    return dsl.select(EVENT_PUBLICATION_ARCHIVE.STATUS)
        .from(EVENT_PUBLICATION_ARCHIVE)
        .where(EVENT_PUBLICATION_ARCHIVE.SERIALIZED_EVENT.contains(id))
        .fetchOptional(EVENT_PUBLICATION_ARCHIVE.STATUS)
        .orElse("");
  }
}
