package com.example.demo.shared.eventresubmission;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.eventresubmission.ResubmissionProbes.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.EventPublicationRegistry;

/**
 * 定期の再投入の時間予算（{@code lock-at-most-for} の半分）を、{@link FailedEventPublications} と古い出版を FAILED
 * に戻す処理のモックで検証する。
 *
 * <p>リスナーは {@code @Async} で別のスレッドで動くため、時計は本物のリスナーでなく、モックの中で {@link ResubmissionOptions} の条件の関数を行ごとに
 * 適用して進める。Spring の文脈は {@link EventPublicationResubmitterRunsTest} と同じ設定で共有する。
 */
@ResubmissionTestSupport.SharedContext
class EventPublicationResubmitterBudgetTest {

  /** 実際のロックの実行。 */
  @Autowired private LockingTaskExecutor lockingTaskExecutor;

  /** リスナーの方針。 */
  @Autowired private ResubmissionPolicies policies;

  /** 設定値。 */
  @Autowired private EventPublicationResubmissionConfig.Properties properties;

  /** 差し替えられる時計。 */
  @Autowired private MutableClock clock;

  /** ロックの期限を動かす DB。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("実行の開始から lock-at-most-for の半分を超えた行は候補にならず、次の回の実行では候補になる")
  void rowsAfterTimeBudgetAreNotCandidatesUntilNextRun() {
    // lock-at-most-for=PT3S なので予算は 1.5 秒。
    final Instant start = Instant.parse("2026-10-10T01:02:03.123456Z");
    clock.set(start);
    expireLock();
    final Duration budget = properties.lockAtMostFor().dividedBy(2);
    final List<Duration> rowOffsets =
        List.of(Duration.ZERO, budget, budget.plus(1, ChronoUnit.MICROS), budget.plusSeconds(1));
    final List<Boolean> accepted = new ArrayList<>();
    final FailedEventPublications failed = Mockito.mock(FailedEventPublications.class);
    Mockito.doAnswer(
            invocation -> {
              final Predicate<EventPublication> filter =
                  invocation.<ResubmissionOptions>getArgument(0).getFilter();
              final Instant runStart = clock.instant();
              for (final Duration offset : rowOffsets) {
                clock.set(runStart.plus(offset));
                accepted.add(filter.test(dueRow(runStart)));
              }
              return null;
            })
        .when(failed)
        .resubmit(Mockito.any(ResubmissionOptions.class));
    final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    final EventPublicationResubmitter resubmitter =
        ResubmissionTestSupport.resubmitter(
            dsl,
            failed,
            lockingTaskExecutor,
            clock,
            properties,
            policies,
            meterRegistry,
            ResubmissionTestSupport.noStaleCheck());

    resubmitter.resubmitOnce();

    assertThat(accepted).as("ちょうど予算は通り、1 マイクロ秒後からは通らない").containsExactly(true, true, false, false);
    assertThat(meterRegistry.get("event.publication.resubmissions").counter().count())
        .as("モックは行を RESUBMITTED に進めないので、進んだ出版は数えない")
        .isZero();

    // 予算を超えた行は FAILED のまま残り、新しい実行（開始時刻が進む）の先頭で候補になる。
    accepted.clear();
    clock.set(start.plusSeconds(10));
    expireLock();
    resubmitter.resubmitOnce();

    assertThat(accepted).as("次の回の先頭の行").first().isEqualTo(true);
  }

  @Test
  @DisplayName("古い出版を FAILED に戻す処理にかかった時間も予算に入り、予算を使い切った回は最初の行も候補にしない")
  void staleCheckTimeCountsAgainstTimeBudget() {
    final Instant start = Instant.parse("2026-10-10T01:02:03.123456Z");
    clock.set(start);
    expireLock();
    final Duration budget = properties.lockAtMostFor().dividedBy(2);
    final EventPublicationRegistry slowStaleCheck = Mockito.mock(EventPublicationRegistry.class);
    Mockito.doAnswer(
            invocation -> {
              clock.set(start.plus(budget).plus(1, ChronoUnit.MICROS));
              return null;
            })
        .when(slowStaleCheck)
        .markStalePublicationsFailed(Mockito.any());
    final List<Boolean> accepted = new ArrayList<>();
    final FailedEventPublications failed = Mockito.mock(FailedEventPublications.class);
    Mockito.doAnswer(
            invocation -> {
              accepted.add(
                  invocation.<ResubmissionOptions>getArgument(0).getFilter().test(dueRow(start)));
              return null;
            })
        .when(failed)
        .resubmit(Mockito.any(ResubmissionOptions.class));

    ResubmissionTestSupport.resubmitter(
            dsl,
            failed,
            lockingTaskExecutor,
            clock,
            properties,
            policies,
            new SimpleMeterRegistry(),
            slowStaleCheck)
        .resubmitOnce();

    assertThat(accepted).as("古い出版を戻すのに予算を使い切った回の最初の行").containsExactly(false);
  }

  /** 待ち時間を十分に過ぎた、default の方針の FAILED の出版。 */
  private static EventPublication dueRow(final Instant runStart) {
    return ResubmissionTestSupport.stubPublication(
        Mockito.mock(EventPublication.class, Mockito.CALLS_REAL_METHODS),
        UUID.randomUUID(),
        1,
        runStart.minus(Duration.ofDays(1)),
        null);
  }

  private void expireLock() {
    ResubmissionTestSupport.expireLock(dsl);
  }
}
