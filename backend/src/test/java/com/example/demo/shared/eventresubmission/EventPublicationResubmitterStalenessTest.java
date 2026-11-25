package com.example.demo.shared.eventresubmission;

import static com.example.demo.shared.eventresubmission.ResubmissionProbes.PROBE_POLICY;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.eventresubmission.EventPublicationResubmitOnce.Target;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.MutableClock;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.ProbeListener;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.ResubmissionProbe;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.core.LockProvider;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication.Status;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;

/**
 * 定期の再投入がロックの中で古い出版を FAILED に戻すこと（{@link FailedPublicationSweep}）を、Spring Modulith
 * の本物のレジストリ、テストのリスナー、ロックで検証する。
 *
 * <p>定期の実行は {@code .env.test} で無効なので、テストが直接呼ぶ。ロックの期限は DB
 * の時刻で決まり、固定の時計では進まないため、ジョブを呼ぶ前に期限を過去にする（{@link #runJob()}）。 Spring の文脈は {@link
 * EventPublicationResubmitterTest} と同じ設定で共有する。
 */
@ResubmissionTestSupport.SharedContext
@EnableScenarios
class EventPublicationResubmitterStalenessTest {

  /** 最初の出版の時刻。マイクロ秒の精度にする。 */
  private static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** {@code event-publication.resubmission.wait-age}。 */
  private static final Duration WAIT_AGE = Duration.ofMinutes(5);

  /** {@code event-publication.resubmission.in-flight-staleness}。 */
  private static final Duration IN_FLIGHT_STALENESS = Duration.ofMinutes(15);

  /** 別のスレッドの完了と、リスナーが門に着くまでを待つ上限の秒数。 */
  private static final long JOIN_TIMEOUT_SECONDS = 10;

  /** 検証対象の定期のジョブ。 */
  @Autowired private EventPublicationResubmitter resubmitter;

  /** 定期のジョブと同じロックを取る、手での 1 回の再投入。 */
  @Autowired private EventPublicationResubmitOnce resubmitOnce;

  /** 差し替えられる時計。古い出版を FAILED に戻す処理もこの時計で古さを測る。 */
  @Autowired private MutableClock clock;

  /** 失敗の回数を決められ、門で止められるテストのリスナー。 */
  @Autowired private ProbeListener listener;

  /** 出版の行を作り、状態を読む。 */
  @Autowired private DSLContext dsl;

  /** ロックを別に持つ。 */
  @Autowired private LockProvider lockProvider;

  @BeforeEach
  void reset() {
    clock.set(T0);
    listener.reset();
    ResubmissionTestSupport.expireLock(dsl);
  }

  /** テストが先に終わっても、リスナーを門に残さない（{@link EventPublicationResubmitterTest#openGate()}）。 */
  @AfterEach
  void openGate() {
    listener.open();
  }

  @Test
  @DisplayName(
      "定期のジョブは、in-flight-staleness より古い出版を手での 1 回の再投入が処理している間も FAILED に戻さず、手での 1 回の再投入は完了まで待つ")
  void jobKeepsPublicationResubmittedOnceInFlight(final Scenario scenario) throws Exception {
    final UUID publication = ResubmissionTestSupport.failedProbe(scenario, listener, dsl);
    // publication_date（T0）からの経過が processing と resubmitted の閾値（in-flight-staleness）を超える。
    clock.set(T0.plus(IN_FLIGHT_STALENESS).plus(Duration.ofMinutes(1)));
    final CountDownLatch gate = listener.close();
    final CompletableFuture<EventPublicationResubmitOnce.Outcome> future =
        ResubmissionTestSupport.onVirtualThread(
            () -> resubmitOnce.run(new Target.Policy(PROBE_POLICY)));
    ResubmissionTestSupport.awaitListenerAtGate(listener, "publication=" + publication);

    runJob();

    assertThat(state(publication).status())
        .as("publication=%s の門で待つ間の状態", publication)
        .isIn(Status.PROCESSING, Status.RESUBMITTED);
    gate.countDown();
    assertThat(future.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .isEqualTo(new EventPublicationResubmitOnce.Outcome(1, 1, 0, 0));
    assertThat(state(publication).isArchived())
        .as("publication=%s の archive", publication)
        .isTrue();
  }

  @Test
  @DisplayName(
      "古い出版は、定期のジョブがロックを取った回にだけ FAILED に戻り、PUBLISHED は wait-age、PROCESSING と RESUBMITTED は"
          + " in-flight-staleness を過ぎたら古いとする")
  void stalePublicationsAreMarkedFailedOnlyWhileTheJobHoldsTheLock() {
    final UUID published = insertStale(Status.PUBLISHED);
    final UUID processing = insertStale(Status.PROCESSING);
    final UUID resubmitted = insertStale(Status.RESUBMITTED);

    clock.set(T0.plus(WAIT_AGE).plus(1, ChronoUnit.MICROS));
    runJob();

    assertThat(statuses(published, processing, resubmitted))
        .as("wait-age を過ぎた回の PUBLISHED、PROCESSING、RESUBMITTED の出版の状態")
        .containsExactly(Status.FAILED, Status.PROCESSING, Status.RESUBMITTED);

    clock.set(T0.plus(IN_FLIGHT_STALENESS).plus(1, ChronoUnit.MICROS));
    ResubmissionTestSupport.whileLockHeld(dsl, lockProvider, clock, resubmitter::resubmitOnce);

    assertThat(statuses(processing, resubmitted))
        .as("in-flight-staleness を過ぎたが、ロックを取れなかった回の状態")
        .containsExactly(Status.PROCESSING, Status.RESUBMITTED);

    runJob();

    assertThat(statuses(processing, resubmitted))
        .as("in-flight-staleness を過ぎ、ロックを取った回の状態")
        .containsExactly(Status.FAILED, Status.FAILED);
  }

  private void runJob() {
    ResubmissionTestSupport.runJob(dsl, resubmitter);
  }

  /**
   * 落ちたまま残った出版の行を、T0 の出版の時刻と決めた状態で作る。
   *
   * <p>回数はテストの上限（2 回）を超えた 3 にし、FAILED に戻した後の同じ回の再投入の候補から外す。
   */
  private UUID insertStale(final Status status) {
    return ResubmissionTestSupport.insertPublication(
        dsl,
        UUID.randomUUID(),
        "staleness-test",
        ResubmissionProbe.class.getName(),
        T0,
        status.name(),
        3);
  }

  private EventPublicationStates.State state(final UUID publication) {
    final EventPublicationStates.@Nullable State state =
        EventPublicationStates.read(dsl, Set.of(publication)).get(publication);
    if (state == null) {
      throw new AssertionError("出版の行も archive の行もない: publication=" + publication);
    }
    return state;
  }

  private List<@Nullable Status> statuses(final UUID... publications) {
    return Arrays.stream(publications).map(id -> state(id).status()).toList();
  }
}
