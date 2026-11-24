package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;

/**
 * {@link FailedPublicationSweep} の interface だけを通して、件数の上限、進んだ出版の判定、ロックの取り方を確かめる。
 *
 * <p>{@link FailedEventPublications} はモックで、条件の関数を行に適用し、{@code advanceable} の行だけ {@code
 * markResubmitted} と同じく {@code completion_attempts} を 1 増やす。
 */
@ResubmissionTestSupport.SharedContext
class FailedPublicationSweepTest {

  /** 固定の現在時刻。 */
  private static final Instant NOW = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** 実際のロックの実行。 */
  @Autowired private LockingTaskExecutor lockingTaskExecutor;

  /** 他のインスタンスの代わりにロックを持つ provider。 */
  @Autowired private LockProvider lockProvider;

  /** 設定値。 */
  @Autowired private EventPublicationResubmissionConfig.Properties properties;

  /** 出版の行を操作する DB。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("max-per-run を超えた出版は再投入せず overLimit に数え、進んだ出版だけを返す")
  void limitsPerRunAndReportsOnlyAdvancedRows() {
    ResubmissionTestSupport.expireLock(dsl);
    final List<UUID> rows = failedRows(3);
    final Set<UUID> advanceable = Set.of(rows.get(1));
    final List<UUID> accepted = new ArrayList<>();
    final FailedPublicationSweep sweep = sweepOver(rows, advanceable, accepted, 2);

    final FailedPublicationSweep.Attempt result =
        sweep.sweep(
            publication -> true, new FailedPublicationSweep.LockMode.TryOnce(Duration.ZERO));

    assertThat(accepted).as("上限の 2 件").containsExactly(rows.get(0), rows.get(1));
    assertThat(result)
        .as("1 件目はモックが進めず、3 件目は上限を超えた")
        .isEqualTo(new FailedPublicationSweep.Attempt.Swept(Set.of(rows.get(1)), 1));
  }

  @Test
  @DisplayName("選ばれなかった出版は、上限にも再投入にも数えない")
  void unselectedRowsAreNeitherResubmittedNorCounted() {
    ResubmissionTestSupport.expireLock(dsl);
    final List<UUID> rows = failedRows(3);
    final List<UUID> accepted = new ArrayList<>();
    final FailedPublicationSweep sweep = sweepOver(rows, Set.copyOf(rows), accepted, 1);
    final Predicate<EventPublication> onlyLast = p -> p.getIdentifier().equals(rows.get(2));

    final FailedPublicationSweep.Attempt result =
        sweep.sweep(onlyLast, new FailedPublicationSweep.LockMode.TryOnce(Duration.ZERO));

    assertThat(accepted).containsExactly(rows.get(2));
    assertThat(result).isEqualTo(new FailedPublicationSweep.Attempt.Swept(Set.of(rows.get(2)), 0));
  }

  @Test
  @DisplayName("他のインスタンスがロックを持つとき、TryOnce は再投入せずに Skipped を返す")
  void tryOnceSkipsWhileLockIsHeld() {
    ResubmissionTestSupport.expireLock(dsl);
    final FailedEventPublications failed = Mockito.mock(FailedEventPublications.class);
    final FailedPublicationSweep sweep = newSweep(failed, properties);
    assertThat(
            lockProvider.lock(
                new LockConfiguration(
                    NOW, FailedPublicationSweep.LOCK_NAME, Duration.ofMinutes(1), Duration.ZERO)))
        .isPresent();
    try {
      final FailedPublicationSweep.Attempt result =
          sweep.sweep(
              publication -> true, new FailedPublicationSweep.LockMode.TryOnce(Duration.ZERO));

      assertThat(result).isEqualTo(new FailedPublicationSweep.Attempt.Skipped());
      Mockito.verifyNoInteractions(failed);
    } finally {
      ResubmissionTestSupport.expireLock(dsl);
    }
  }

  /** {@code rows} を FAILED の行として {@code resubmit} に流すモックの上の sweep。 */
  private FailedPublicationSweep sweepOver(
      final List<UUID> rows,
      final Set<UUID> advanceable,
      final List<UUID> accepted,
      final int maxPerRun) {
    final FailedEventPublications failed = Mockito.mock(FailedEventPublications.class);
    Mockito.doAnswer(
            invocation -> {
              final Predicate<EventPublication> filter =
                  invocation.<ResubmissionOptions>getArgument(0).getFilter();
              for (final UUID id : rows) {
                if (filter.test(row(id))) {
                  accepted.add(id);
                  if (advanceable.contains(id)) {
                    dsl.update(EVENT_PUBLICATION)
                        .set(EVENT_PUBLICATION.COMPLETION_ATTEMPTS, 2)
                        .where(EVENT_PUBLICATION.ID.eq(id))
                        .execute();
                  }
                }
              }
              return null;
            })
        .when(failed)
        .resubmit(Mockito.any(ResubmissionOptions.class));
    return newSweep(
        failed,
        new EventPublicationResubmissionConfig.Properties(
            properties.waitAge(),
            properties.inFlightStaleness(),
            properties.interval(),
            maxPerRun,
            properties.lockAtMostFor(),
            properties.policies()));
  }

  private FailedPublicationSweep newSweep(
      final FailedEventPublications failed,
      final EventPublicationResubmissionConfig.Properties sweepProperties) {
    return new FailedPublicationSweep(
        dsl,
        failed,
        lockingTaskExecutor,
        java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
        sweepProperties);
  }

  /** completion_attempts が 1 の FAILED の行を DB に作る。 */
  private List<UUID> failedRows(final int count) {
    final List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add(
          ResubmissionTestSupport.insertPublication(
              dsl,
              UUID.randomUUID(),
              "sweep-test",
              NOW.minus(Duration.ofDays(1)),
              EventPublication.Status.FAILED.name(),
              1));
    }
    return ids;
  }

  private static EventPublication row(final UUID id) {
    return ResubmissionTestSupport.stubPublication(
        Mockito.mock(EventPublication.class, Mockito.CALLS_REAL_METHODS),
        id,
        1,
        NOW.minus(Duration.ofDays(1)),
        null);
  }
}
