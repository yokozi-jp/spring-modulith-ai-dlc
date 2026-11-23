package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.infrastructure.persistence.EventPublicationResubmitterTest.ProbeListener;
import com.example.demo.shared.infrastructure.persistence.EventPublicationResubmitterTest.ResubmissionProbe;
import com.example.demo.testkit.AsTenant;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.DefaultEventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;

/**
 * Spring Modulith 2.2.0 の {@link FailedEventPublications#resubmit(ResubmissionOptions)}、Staleness
 * Monitor（{@link EventPublicationRegistry#markStalePublicationsFailed}）、{@code AbandonPolicy}
 * の、このアプリが頼る振る舞いを固定する学習テスト（ADR-079）。
 *
 * <p>{@link EventPublicationResubmitter} と {@link EventPublicationResubmitOnce} は、{@code LIMIT}
 * が条件の関数より先に掛かるため、{@code withBatchSize} を {@link Integer#MAX_VALUE} にして FAILED の行をすべて読む。 Spring
 * Modulith の版を上げて {@code LIMIT} のテストが失敗したら、ADR-079 と両方のクラスの読み方を見直す。 再投入が {@code publication_date}
 * が今より前の出版だけを読むことには、時計を止めて出版を作るテストが頼る。
 *
 * <p>Staleness Monitor のテストが失敗したら、{@code event-publication.resubmission.in-flight-staleness}
 * の起動時の検証と ADR-079 を見直す。 {@code stalenessMarksPublicationResubmittedAfterRead} が失敗したら、Spring
 * Modulith が読んだ後の再投入を守るようになったので、{@code application.yaml} の注記と ADR-079 の該当の Consequences を消す。 どの行も
 * FAILED で終わるため、{@code CleanGeneratedTablesExtension} の処理中の出版の待ちを止めない。
 *
 * <p>{@code abandonPolicySeesAttemptsReadBeforeResubmission} が失敗したら、{@link
 * ResubmissionPolicies#shouldAbandon} が DB から回数を読む理由がなくなったので、渡された出版の回数で判断するよう見直す。
 */
// 再投入、Staleness Monitor、諦めの判断の振る舞いを同じ文脈で固定し、行を読む補助を持つため、メソッドの数が多い。
@SuppressWarnings("PMD.TooManyMethods")
@ResubmissionTestSupport.SharedContext
@EnableScenarios
class ResubmissionOptionsLearningTest {

  /** FAILED の状態。 */
  private static final String FAILED_STATUS = "FAILED";

  /** 最初の出版の時刻。マイクロ秒の精度にする。 */
  private static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** Staleness Monitor のテストの閾値。 */
  private static final Duration STALENESS = Duration.ofMinutes(5);

  /** Spring Modulith の失敗した出版の再投入。 */
  @Autowired private FailedEventPublications failedEventPublications;

  /** 失敗の回数を決められるテストのリスナー。 */
  @Autowired private ProbeListener listener;

  /** 出版の状態を読む。 */
  @Autowired private DSLContext dsl;

  /** Staleness Monitor が呼ぶ、古い出版を FAILED に戻す処理。 */
  @Autowired private EventPublicationRegistry registry;

  /** Spring Modulith が出版を読み書きする Repository。 */
  @Autowired private EventPublicationRepository repository;

  /** 出版の時刻を決める時計。 */
  @Autowired private EventPublicationResubmitterTest.MutableClock clock;

  /** Spring Modulith が諦めの判断に渡す出版の回数。 */
  @Autowired private EventPublicationResubmitterTest.AbandonPolicyRecorder abandonPolicies;

  @BeforeEach
  void reset() {
    listener.reset();
  }

  @Test
  @DisplayName("学習テスト：withBatchSize の LIMIT は条件の関数より先に掛かり、古い出版が枠を占めると新しい出版に届かない")
  void batchSizeLimitIsAppliedBeforeFilter(final Scenario scenario) {
    // ORDER BY publication_date の順を決めるため、出版の時刻をずらす。
    clock.set(T0);
    final UUID older = failed(scenario);
    clock.set(T0.plusSeconds(1));
    final UUID newer = failed(scenario);
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
    final UUID publication = failed(scenario);

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
      "学習テスト：再投入した出版が失敗したとき、AbandonPolicy に渡る出版の completionAttempts は、再投入で 1 増えた行の値でなく再投入の前に読んだ値である")
  void abandonPolicySeesAttemptsReadBeforeResubmission(final Scenario scenario) {
    clock.set(T0);
    final UUID publication = failed(scenario);
    listener.failTimes(1);
    clock.set(T0.plus(1, ChronoUnit.MICROS));

    failedEventPublications.resubmit(ResubmissionOptions.defaults());

    scenario
        .stimulate(() -> {})
        .andWaitForStateChange(
            () -> FAILED_STATUS.equals(status(publication)) && attempts(publication) == 2,
            Boolean.TRUE::equals)
        .andVerify(done -> {});
    assertThat(abandonPolicies.seen(publication))
        .as("最初の配信の失敗と、1 回目の再投入の失敗で渡された completionAttempts")
        .containsExactly(1, 1);
  }

  @ParameterizedTest
  @EnumSource(
      value = EventPublication.Status.class,
      names = {"PROCESSING", "RESUBMITTED"})
  @DisplayName("学習テスト：Staleness Monitor は処理中の出版を publication_date でなく last_resubmission_date から測る")
  void stalenessIsMeasuredFromLastResubmissionDate(final EventPublication.Status inFlight) {
    final UUID publication = insert(inFlight);
    final Instant resubmittedAt = T0.plus(Duration.ofMinutes(10));
    dsl.update(EVENT_PUBLICATION)
        .set(EVENT_PUBLICATION.LAST_RESUBMISSION_DATE, resubmittedAt)
        .where(EVENT_PUBLICATION.ID.eq(publication))
        .execute();
    clock.set(resubmittedAt);

    registry.markStalePublicationsFailed(status -> STALENESS);

    assertThat(status(publication)).as("再投入した直後の出版の状態").isEqualTo(inFlight.name());

    clock.set(resubmittedAt.plus(STALENESS).plus(1, ChronoUnit.MICROS));

    registry.markStalePublicationsFailed(status -> STALENESS);

    assertThat(status(publication)).as("再投入から閾値を過ぎた出版の状態").isEqualTo(FAILED_STATUS);
  }

  @Test
  @DisplayName("学習テスト：Staleness Monitor は、古い出版を読んだ後に再投入された出版も読んだときの状態を確かめずに FAILED にする")
  void stalenessMarksPublicationResubmittedAfterRead() {
    final UUID publication = insert(EventPublication.Status.PUBLISHED);
    final Instant now = T0.plus(STALENESS).plus(1, ChronoUnit.MICROS);
    clock.set(now);
    final EventPublicationRepository wrapper =
        Mockito.mock(EventPublicationRepository.class, AdditionalAnswers.delegatesTo(repository));
    // 読んだ後、markFailed の前に、別のインスタンスが FAILED にして再投入した状態にする。
    Mockito.doAnswer(
            call -> {
              final List<TargetEventPublication> read =
                  repository.findByStatus(call.getArgument(0));
              repository.markFailed(publication);
              repository.markResubmitted(publication, now);
              return read;
            })
        .when(wrapper)
        .findByStatus(EventPublication.Status.PUBLISHED);

    new DefaultEventPublicationRegistry(wrapper, clock)
        .markStalePublicationsFailed(status -> STALENESS);

    assertThat(status(publication)).as("再投入の直後に遅れて届いた markFailed の後の状態").isEqualTo(FAILED_STATUS);
    assertThat(
            dsl.select(EVENT_PUBLICATION.LAST_RESUBMISSION_DATE)
                .from(EVENT_PUBLICATION)
                .where(EVENT_PUBLICATION.ID.eq(publication))
                .fetchSingle(EVENT_PUBLICATION.LAST_RESUBMISSION_DATE))
        .as("FAILED に戻された出版の最後の再投入の時刻")
        .isEqualTo(now);
  }

  /**
   * リスナーを持たない出版の行を、T0 の出版の時刻と決めた状態で作る。
   *
   * <p>Staleness Monitor は行のイベントを読み戻すため、読み戻せるテストのイベントの型と本文にする。
   */
  private UUID insert(final EventPublication.Status status) {
    final UUID publication =
        ResubmissionTestSupport.insertPublication(
            dsl, UUID.randomUUID(), "staleness-learning", T0, status.name(), 1);
    dsl.update(EVENT_PUBLICATION)
        .set(EVENT_PUBLICATION.EVENT_TYPE, ResubmissionProbe.class.getName())
        .set(EVENT_PUBLICATION.SERIALIZED_EVENT, "{\"id\":\"" + publication + "\"}")
        .where(EVENT_PUBLICATION.ID.eq(publication))
        .execute();
    return publication;
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

  private @Nullable String status(final UUID publication) {
    return dsl.select(EVENT_PUBLICATION.STATUS)
        .from(EVENT_PUBLICATION)
        .where(EVENT_PUBLICATION.ID.eq(publication))
        .fetchSingle(EVENT_PUBLICATION.STATUS);
  }

  /** リスナーを 1 回失敗させて FAILED の出版を作り、その ID を返す。 */
  private UUID failed(final Scenario scenario) {
    listener.failTimes(1);
    final String id = UUID.randomUUID().toString();
    final AtomicReference<@Nullable UUID> publication = new AtomicReference<>();
    scenario
        .publish(new ResubmissionProbe(id, AsTenant.TENANT))
        .andWaitForStateChange(() ->
            dsl.select(EVENT_PUBLICATION.ID)
                .from(EVENT_PUBLICATION)
                .where(
                    EVENT_PUBLICATION
                        .SERIALIZED_EVENT
                        .contains(id)
                        .and(EVENT_PUBLICATION.STATUS.eq(FAILED_STATUS)))
                .fetchOptional(EVENT_PUBLICATION.ID)
                .orElse(null))
        .andVerify(publication::set);
    return Objects.requireNonNull(publication.get(), "FAILED の出版の ID");
  }
}
