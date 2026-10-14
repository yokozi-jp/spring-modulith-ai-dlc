package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.infrastructure.persistence.EventPublicationResubmitterTest.ProbeListener;
import com.example.demo.shared.infrastructure.persistence.EventPublicationResubmitterTest.ResubmissionProbe;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;

/**
 * Spring Modulith 2.1.1 の {@link FailedEventPublications#resubmit(ResubmissionOptions)} と Staleness
 * Monitor（{@link EventPublicationRegistry#markStalePublicationsFailed}）の癖を固定する学習テスト（ADR-079）。
 *
 * <p>{@link EventPublicationResubmitter} と {@link EventPublicationResubmitOnce} は、この癖を前提に、{@code
 * withMinAge} に頼らず条件の関数で待ち時間を判定し、{@code withBatchSize} を {@link Integer#MAX_VALUE} にして FAILED
 * の行をすべて読む。 Spring Modulith の版を上げてこのテストが失敗したら、癖が直ったので、ADR-079 と両方のクラスの読み方（{@code LIMIT} と待ち時間）を見直す。
 *
 * <p>Staleness Monitor の 2 つの学習テストが失敗したら（2.1.2 の GH-1837 と GH-1918 で直る）、{@code
 * event-publication.resubmission.in-flight-staleness} と ADR-079 を見直す。 どちらの行も FAILED で終わるため、{@code
 * CleanGeneratedTablesExtension} の処理中の出版の待ちを止めない。
 */
@ResubmissionTestSupport.SharedContext
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

  /** Staleness Monitor が呼ぶ、古い出版を FAILED に戻す処理。 */
  @Autowired private EventPublicationRegistry registry;

  /** 出版の時刻を決める時計。 */
  @Autowired private EventPublicationResubmitterTest.MutableClock clock;

  @BeforeEach
  void reset() {
    listener.reset();
  }

  @Test
  @DisplayName("学習テスト：withMinAge は FAILED の出版を絞らず、作ったばかりの出版も再投入する")
  void minAgeDoesNotFilterFailedPublications(final Scenario scenario) {
    final UUID publication = failed(scenario);

    // 2.1.1 の SQL は「STATUS = 'FAILED' OR (STATUS IS NULL AND ...) AND PUBLICATION_DATE < ?」で、AND が
    // OR より先に結びつく。
    failedEventPublications.resubmit(
        ResubmissionOptions.defaults().withMinAge(Duration.ofDays(36_500)));

    scenario
        .stimulate(() -> {})
        .andWaitForStateChange(() -> archived(publication), Boolean.TRUE::equals)
        .andVerify(done -> {});
    assertThat(archived(publication)).as("100 年の最小の経過時間でも再投入して完了した").isTrue();
  }

  @Test
  @DisplayName("学習テスト：withBatchSize の LIMIT は条件の関数より先に掛かり、古い出版が枠を占めると新しい出版に届かない")
  void batchSizeLimitIsAppliedBeforeFilter(final Scenario scenario) {
    // ORDER BY publication_date の順を決めるため、出版の時刻をずらす。
    clock.set(T0);
    final UUID older = failed(scenario);
    clock.set(T0.plusSeconds(1));
    final UUID newer = failed(scenario);
    final List<UUID> seen = new CopyOnWriteArrayList<>();

    failedEventPublications.resubmit(
        ResubmissionOptions.defaults()
            .withBatchSize(1)
            .withFilter(
                publication -> {
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
      "学習テスト：Staleness Monitor は RESUBMITTED の出版を last_resubmission_date でなく publication_date から測る")
  void stalenessIsMeasuredFromPublicationDate() {
    final UUID publication = insert(EventPublication.Status.RESUBMITTED);
    dsl.update(EVENT_PUBLICATION)
        .set(EVENT_PUBLICATION.LAST_RESUBMISSION_DATE, T0.plus(Duration.ofMinutes(10)))
        .where(EVENT_PUBLICATION.ID.eq(publication))
        .execute();
    clock.set(T0.plus(Duration.ofMinutes(10)));

    registry.markStalePublicationsFailed(status -> Duration.ofMinutes(5));

    assertThat(status(publication)).as("再投入した直後の出版の状態").isEqualTo(FAILED_STATUS);
  }

  @Test
  @DisplayName("学習テスト：状態ごとの Duration.ZERO はその状態を止めず、その状態の出版をすべて FAILED にする")
  void zeroStalenessMarksAllPublicationsOfThatStateFailed() {
    final UUID publication = insert(EventPublication.Status.PROCESSING);
    clock.set(T0.plusSeconds(1));

    registry.markStalePublicationsFailed(
        status ->
            status == EventPublication.Status.PROCESSING ? Duration.ZERO : Duration.ofHours(1));

    assertThat(status(publication)).as("1 秒前の PROCESSING の出版の状態").isEqualTo(FAILED_STATUS);
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
        .publish(new ResubmissionProbe(id))
        .andWaitForStateChange(
            () ->
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

  private boolean archived(final UUID publication) {
    return dsl.fetchExists(EVENT_PUBLICATION_ARCHIVE, EVENT_PUBLICATION_ARCHIVE.ID.eq(publication));
  }
}
