package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.eventresubmission.ResubmissionProbes.MutableClock;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.ProbeListener;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;

/**
 * Spring Modulith 2.2.0 の {@link FailedEventPublications#resubmit(ResubmissionOptions)} と {@code
 * AbandonPolicy} の、このアプリが頼る振る舞いを固定する学習テスト（ADR-079）。
 *
 * <p>{@link EventPublicationResubmitter} と {@link EventPublicationResubmitOnce} は、{@code LIMIT}
 * が条件の関数より先に掛かるため、{@code withBatchSize} を {@link Integer#MAX_VALUE} にして FAILED の行をすべて読む。 Spring
 * Modulith の版を上げて {@code LIMIT} のテストが失敗したら、ADR-079 と両方のクラスの読み方を見直す。 再投入が {@code publication_date}
 * が今より前の出版だけを読むことには、時計を止めて出版を作るテストが頼る。
 *
 * <p>{@code abandonPolicySeesPublicationReadBeforeResubmission} が失敗したら、{@code
 * spring.modulith.events.resubmission.abandon-after-attempts} の数え方が変わったので、{@link
 * EventPublicationResubmissionConfig.AbandonProperties} の下限と {@link ResubmissionPolicies#isDue}
 * の回数の条件と ADR-079 を見直す。
 */
@ResubmissionTestSupport.SharedContext
// 諦めの判断を記録する bean は、他の再投入のテストの文脈に入れない。
@Import(AbandonPolicyRecorder.class)
@EnableScenarios
class ResubmissionOptionsLearningTest {

  /** FAILED の状態。 */
  private static final String FAILED_STATUS = "FAILED";

  /** 最初の出版の時刻。マイクロ秒の精度にする。 */
  private static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** Spring Modulith の失敗した出版の再投入。 */
  @Autowired private FailedEventPublications failedEventPublications;

  /** 失敗の回数を決められるテストのリスナー。 */
  @Autowired private ProbeListener listener;

  /** 出版の状態を読む。 */
  @Autowired private DSLContext dsl;

  /** 出版の時刻を決める時計。 */
  @Autowired private MutableClock clock;

  /** Spring Modulith が諦めの判断に渡す出版の回数。 */
  @Autowired private AbandonPolicyRecorder abandonPolicies;

  @BeforeEach
  void reset() {
    listener.reset();
  }

  @Test
  @DisplayName("学習テスト：withBatchSize の LIMIT は条件の関数より先に掛かり、古い出版が枠を占めると新しい出版に届かない")
  void batchSizeLimitIsAppliedBeforeFilter(final Scenario scenario) {
    // ORDER BY publication_date の順を決めるため、出版の時刻をずらす。
    clock.set(T0);
    final UUID older = ResubmissionTestSupport.failedProbe(scenario, listener, dsl);
    clock.set(T0.plusSeconds(1));
    final UUID newer = ResubmissionTestSupport.failedProbe(scenario, listener, dsl);
    // 新しい出版も読める時刻に進める（defaultsResubmitOnlyPublicationsOlderThanNow）。
    clock.set(T0.plusSeconds(2));
    final List<UUID> seen = new CopyOnWriteArrayList<>();

    failedEventPublications.resubmit(
        ResubmissionOptions.defaults()
            .withBatchSize(1)
            .withFilter(publication -> {
              seen.add(publication.getIdentifier());
              return newer.equals(publication.getIdentifier());
            }));

    assertThat(seen).as("条件の関数が受け取った出版").containsExactly(older);
    assertThat(
            dsl.select(EVENT_PUBLICATION.STATUS)
                .from(EVENT_PUBLICATION)
                .where(EVENT_PUBLICATION.ID.eq(newer))
                .fetchSingle(EVENT_PUBLICATION.STATUS))
        .as("条件に合う新しい出版の状態")
        .isEqualTo(FAILED_STATUS);
  }

  @Test
  @DisplayName(
      "学習テスト：ResubmissionOptions.defaults() は publication_date が今と同じ FAILED の出版を再投入せず、1 マイクロ秒後には再投入する")
  void defaultsResubmitOnlyPublicationsOlderThanNow(final Scenario scenario) {
    clock.set(T0);
    final UUID publication = ResubmissionTestSupport.failedProbe(scenario, listener, dsl);

    failedEventPublications.resubmit(ResubmissionOptions.defaults());

    assertThat(status(publication)).as("publication_date が今と同じ出版の状態").isEqualTo(FAILED_STATUS);

    clock.set(T0.plus(1, ChronoUnit.MICROS));
    failedEventPublications.resubmit(ResubmissionOptions.defaults());

    scenario
        .stimulate(() -> {})
        .andWaitForStateChange(() -> archived(publication), Boolean.TRUE::equals)
        .andVerify(done -> {});
    assertThat(archived(publication)).as("1 マイクロ秒後に再投入して完了した").isTrue();
  }

  @Test
  @DisplayName(
      "学習テスト：AbandonPolicy に渡る出版は、最初の配信では作ったときの PUBLISHED の値、再投入では再投入の前に読んだ FAILED の行の値で、"
          + "abandon-after-attempts=2 は 1 回目の再投入の失敗で諦めず、2 回目の再投入の失敗で諦める")
  void abandonPolicySeesPublicationReadBeforeResubmission(final Scenario scenario) {
    clock.set(T0);
    final UUID publication = ResubmissionTestSupport.failedProbe(scenario, listener, dsl);
    final Instant first = T0.plus(1, ChronoUnit.MICROS);
    final Instant second = T0.plus(2, ChronoUnit.MICROS);
    listener.failTimes(2);

    clock.set(first);
    failedEventPublications.resubmit(ResubmissionOptions.defaults());
    scenario
        .stimulate(() -> {})
        .andWaitForStateChange(
            () -> FAILED_STATUS.equals(status(publication)) && attempts(publication) == 2,
            Boolean.TRUE::equals)
        .andVerify(done -> {});
    clock.set(second);
    failedEventPublications.resubmit(ResubmissionOptions.defaults());
    scenario
        .stimulate(() -> {})
        .andWaitForStateChange(() -> archivedStatus(publication), "ABANDONED"::equals)
        .andVerify(done -> {});

    assertThat(abandonPolicies.seen(publication))
        .as("最初の配信、1 回目と 2 回目の再投入の失敗で渡された出版。最初の 2 つは状態だけが違う")
        .containsExactly(
            new AbandonPolicyRecorder.Seen(EventPublication.Status.PUBLISHED, T0, T0, 1, T0),
            new AbandonPolicyRecorder.Seen(EventPublication.Status.FAILED, T0, T0, 1, T0),
            new AbandonPolicyRecorder.Seen(EventPublication.Status.FAILED, first, T0, 2, first));
  }

  private int attempts(final UUID publication) {
    return Objects.requireNonNull(
        dsl.select(EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.ID.eq(publication))
            .fetchSingle(EVENT_PUBLICATION.COMPLETION_ATTEMPTS),
        "completion_attempts");
  }

  private boolean archived(final UUID publication) {
    return dsl.fetchExists(EVENT_PUBLICATION_ARCHIVE, EVENT_PUBLICATION_ARCHIVE.ID.eq(publication));
  }

  private @Nullable String archivedStatus(final UUID publication) {
    return dsl.select(EVENT_PUBLICATION_ARCHIVE.STATUS)
        .from(EVENT_PUBLICATION_ARCHIVE)
        .where(EVENT_PUBLICATION_ARCHIVE.ID.eq(publication))
        .fetchOptional(EVENT_PUBLICATION_ARCHIVE.STATUS)
        .orElse(null);
  }

  private @Nullable String status(final UUID publication) {
    return dsl.select(EVENT_PUBLICATION.STATUS)
        .from(EVENT_PUBLICATION)
        .where(EVENT_PUBLICATION.ID.eq(publication))
        .fetchSingle(EVENT_PUBLICATION.STATUS);
  }
}
