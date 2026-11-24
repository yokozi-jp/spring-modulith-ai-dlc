package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
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

  /** 設定値。 */
  @Autowired private EventPublicationResubmissionConfig.Properties properties;

  /** 出版の行を操作する DB。 */
  @Autowired private DSLContext dsl;

  /** 前のテストのロックを残さない。 */
  @BeforeEach
  void expireLock() {
    ResubmissionTestSupport.expireLock(dsl);
  }

  @Test
  @DisplayName("max-per-run を超えた出版は再投入せず remaining に数え、進んだ出版だけを返す")
  void limitsPerRunAndReportsOnlyAdvancedRows() {
    final List<UUID> rows = failedRows(3);
    final List<UUID> accepted = new ArrayList<>();
    final FailedPublicationSweep sweep = sweepOver(rows, Set.of(rows.get(1)), accepted, 2);

    final Optional<FailedPublicationSweep.Swept> swept = sweep.tryOnce(publication -> true);

    assertThat(accepted).as("上限の 2 件").containsExactly(rows.get(0), rows.get(1));
    assertThat(swept)
        .as("1 件目はモックが進めず、3 件目は上限を超えた")
        .contains(new FailedPublicationSweep.Swept(Map.of(rows.get(1), 2), 1));
  }

  @Test
  @DisplayName("選ばれなかった出版は、上限にも再投入にも数えない")
  void unselectedRowsAreNeitherResubmittedNorCounted() {
    final List<UUID> rows = failedRows(3);
    final List<UUID> accepted = new ArrayList<>();
    final FailedPublicationSweep sweep = sweepOver(rows, Set.copyOf(rows), accepted, 1);

    final Optional<FailedPublicationSweep.Swept> swept =
        sweep.tryOnce(publication -> publication.getIdentifier().equals(rows.get(2)));

    assertThat(accepted).containsExactly(rows.get(2));
    assertThat(swept).contains(new FailedPublicationSweep.Swept(Map.of(rows.get(2), 2), 0));
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
    return ResubmissionTestSupport.sweep(
        dsl,
        failed,
        lockingTaskExecutor,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new EventPublicationResubmissionConfig.Properties(
            properties.waitAge(),
            properties.inFlightStaleness(),
            properties.interval(),
            maxPerRun,
            properties.lockAtMostFor(),
            properties.policies()));
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
