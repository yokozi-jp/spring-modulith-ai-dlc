package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.jooq.modulith.Tables;
import com.example.demo.testkit.CommittedDatabaseTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.test.context.TestPropertySource;

/**
 * 定期の再投入の時間予算（{@code lock-at-most-for} の半分）を、{@link FailedEventPublications} のモックで検証する。
 *
 * <p>リスナーは {@code @Async} で別のスレッドで動くため、時計は本物のリスナーでなく、モックの中で {@link ResubmissionOptions} の条件の関数を行ごとに
 * 適用して進める。Spring の文脈は {@link EventPublicationResubmitterRunsTest} と同じ設定で共有する。
 */
@CommittedDatabaseTest
@Import(EventPublicationResubmitterTest.ProbeConfiguration.class)
@TestPropertySource(
    properties = {
      "event-publication.resubmission.wait-age=PT5M",
      "event-publication.resubmission.interval=PT2S",
      "event-publication.resubmission.lock-at-most-for=PT3S",
      "event-publication.resubmission.max-per-run=100",
      "event-publication.resubmission.policies.default.max-resubmissions=1",
      "event-publication.resubmission.policies.probe.max-resubmissions=2",
      "event-publication.resubmission.policies.probe.multiplier=2",
      "event-publication.resubmission.policies.probe.max-wait=PT20M"
    })
class EventPublicationResubmitterBudgetTest {

  /** 実際のロックの実行。 */
  @Autowired private LockingTaskExecutor lockingTaskExecutor;

  /** リスナーの方針。 */
  @Autowired private ResubmissionPolicies policies;

  /** 設定値。 */
  @Autowired private EventPublicationResubmissionConfig.Properties properties;

  /** 差し替えられる時計。 */
  @Autowired private EventPublicationResubmitterTest.MutableClock clock;

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
        new EventPublicationResubmitter(
            dsl, failed, lockingTaskExecutor, policies, clock, meterRegistry, properties);

    resubmitter.resubmitOnce();

    assertThat(accepted).as("ちょうど予算は通り、1 マイクロ秒後からは通らない").containsExactly(true, true, false, false);
    assertThat(meterRegistry.get("event.publication.resubmissions").counter().count())
        .isEqualTo(2.0);

    // 予算を超えた行は FAILED のまま残り、新しい実行（開始時刻が進む）の先頭で候補になる。
    accepted.clear();
    clock.set(start.plusSeconds(10));
    expireLock();
    resubmitter.resubmitOnce();

    assertThat(accepted).as("次の回の先頭の行").first().isEqualTo(true);
  }

  /** 待ち時間を十分に過ぎた、default の方針の FAILED の出版。 */
  private static EventPublication dueRow(final Instant runStart) {
    final EventPublication publication = Mockito.mock(EventPublication.class);
    Mockito.when(publication.getPublicationDate()).thenReturn(runStart.minus(Duration.ofDays(1)));
    return publication;
  }

  /** ロックの期限を過去にする。期限は DB の時刻で決まり、固定の時計では進まないため。 */
  private void expireLock() {
    dsl.update(Tables.SHEDLOCK)
        .set(Tables.SHEDLOCK.LOCK_UNTIL, Tables.SHEDLOCK.LOCKED_AT)
        .execute();
  }
}
