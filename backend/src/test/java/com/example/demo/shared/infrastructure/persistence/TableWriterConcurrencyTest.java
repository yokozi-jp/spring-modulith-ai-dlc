package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.infrastructure.persistence.TableWriterTest.ItemConflictException;
import com.example.demo.testkit.DatabaseTest;
import com.example.demo.testkit.FixtureTablesExtension;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.transaction.AfterTransaction;

/**
 * {@link TableWriter} の競合の判定を、二つのセッションと実 PostgreSQL で確かめる。
 *
 * <p>テストのトランザクション（A）が {@link TableWriter} で書き、{@link DataSource} から取った二つ目の接続（B）が先に同じ行を更新する。B
 * の行はコミットするため、テストのトランザクションが終わった後に自動コミットの接続で消す。接続は A、B、待ちを見張る接続の三つで、テストの pool の上限 4 に収まる。
 */
@DatabaseTest
@ExtendWith(FixtureTablesExtension.class)
class TableWriterConcurrencyTest {

  /** 各テストで使う行の主キー。 */
  private static final long ITEM_ID = 1L;

  /** 更新のユースケースとして束縛する pgm_cd。 */
  private static final String PGM_CD = "shared.TableWriterConcurrencyTest";

  /** 現在のスパンの trace ID。 */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** B のコミットを待つ上限。 */
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

  /** テストのトランザクション（A）の jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** B と見張りの接続を取る、アプリロールの接続 pool。 */
  @Autowired private DataSource dataSource;

  /** 共通カラムの値を作る共通処理。 */
  private CommonColumns commonColumns;

  /** 検証対象。A の接続で書く。 */
  private TableWriter writer;

  @BeforeEach
  void setUp() {
    commonColumns =
        new CommonColumns(
            Clock.fixed(Instant.parse("2026-10-04T01:02:03.123456Z"), ZoneOffset.UTC),
            TracerStubs.withTraceId(TRACE_ID));
    writer = new TableWriter(dsl, commonColumns);
  }

  // A は待った後の再評価で行ロックを取り、ロールバックまで持つため、テストのトランザクションが終わってから消す。
  @AfterTransaction
  /* package */ void deleteCommittedRows() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(true);
      DSL.using(connection, SQLDialect.POSTGRES).deleteFrom(FIXTURE_ITEM).execute();
    }
  }

  @Test
  @DisplayName("B の更新のコミットを待った A の古い版の保存は、原因なしの競合の例外になり、B の値を上書きしない")
  // A がロックを待つ間に B をコミットするため、見張りを別のスレッドで動かす。
  @SuppressWarnings("PMD.DoNotUseThreads")
  void staleSaveWaitingForCommitConflicts() throws Exception {
    try (ExecutorService watcher = Executors.newSingleThreadExecutor();
        Connection sessionB = openSessionB()) {
      updateInSessionB(sessionB);
      final Future<Boolean> committed =
          watcher.submit(() -> commitWhenAnotherSessionWaits(sessionB));

      assertThatThrownBy(() -> inUseCase(() -> updateItem(1L)))
          .isInstanceOf(ItemConflictException.class)
          .hasMessageContaining("row was updated by another request")
          .hasNoCause();
      assertThat(committed.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS))
          .as("A がロックを待ってから B がコミットした")
          .isTrue();
      final Record row =
          DSL.using(sessionB, SQLDialect.POSTGRES)
              .selectFrom(FIXTURE_ITEM)
              .where(FIXTURE_ITEM.ITEM_ID.eq(ITEM_ID))
              .fetchSingle();
      assertThat(row.get(FIXTURE_ITEM.ITEM_NAME)).as("B の値").isEqualTo("B");
      assertThat(row.get(FIXTURE_ITEM.LOCK_NO)).as("B の版").isEqualTo(2L);
    }
  }

  @Test
  @DisplayName("B が行ロックを持ち続けると、A の updateCheckingVersion は lock_timeout で競合の例外になる")
  void lockTimeoutBecomesConflict() throws SQLException {
    try (Connection sessionB = openSessionB()) {
      updateInSessionB(sessionB);

      assertThatThrownBy(() -> inUseCase(() -> updateItem(1L)))
          .isInstanceOf(ItemConflictException.class)
          .hasMessageContaining("row is locked by another request")
          .hasCauseInstanceOf(CannotAcquireLockException.class);
      sessionB.rollback();
    }
  }

  @Test
  @DisplayName("B が行ロックを持ち続けると、A の updateWhere は CannotAcquireLockException をそのまま投げる")
  void lockTimeoutPropagatesFromUpdateWhere() throws SQLException {
    try (Connection sessionB = openSessionB()) {
      updateInSessionB(sessionB);

      assertThatThrownBy(
              () ->
                  inUseCase(
                      () ->
                          writer.updateWhere(
                              FIXTURE_ITEM,
                              FIXTURE_ITEM.ITEM_ID.eq(ITEM_ID),
                              set -> set.set(FIXTURE_ITEM.ITEM_NAME, "A"))))
          .isInstanceOf(CannotAcquireLockException.class);
      sessionB.rollback();
    }
  }

  /** B の接続を開き、{@code lock_no = 1} の行を作ってコミットする。以降の B の文は自動コミットしない。 */
  private Connection openSessionB() throws SQLException {
    final Connection sessionB = dataSource.getConnection();
    sessionB.setAutoCommit(false);
    ScopedValue.where(PgmCdAspect.PGM_CD, PGM_CD)
        .run(
            () ->
                DSL.using(sessionB, SQLDialect.POSTGRES)
                    .insertInto(FIXTURE_ITEM)
                    .set(commonColumns.forInsert(FIXTURE_ITEM))
                    .set(FIXTURE_ITEM.ITEM_ID, ITEM_ID)
                    .set(FIXTURE_ITEM.ITEM_NAME, "seed")
                    .execute());
    sessionB.commit();
    return sessionB;
  }

  /** B で行を版 2 に更新し、コミットせずに行ロックを持つ。 */
  private static void updateInSessionB(final Connection sessionB) {
    DSL.using(sessionB, SQLDialect.POSTGRES)
        .update(FIXTURE_ITEM)
        .set(FIXTURE_ITEM.ITEM_NAME, "B")
        .set(FIXTURE_ITEM.LOCK_NO, 2L)
        .where(FIXTURE_ITEM.ITEM_ID.eq(ITEM_ID))
        .execute();
  }

  /**
   * 別のセッションが行ロックを待ち始めたら B をコミットする。待ちが見えないまま上限を過ぎたら、B をコミットして {@code false} を返す。
   *
   * <p>A の {@code lock_timeout}（テストでは 1 秒）より十分短い 10 ミリ秒ごとに {@code pg_stat_activity} を見る。
   */
  private boolean commitWhenAnotherSessionWaits(final Connection sessionB)
      throws SQLException, InterruptedException {
    try (Connection monitor = dataSource.getConnection()) {
      monitor.setAutoCommit(true);
      final DSLContext monitorDsl = DSL.using(monitor, SQLDialect.POSTGRES);
      final long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
      boolean waiting = false;
      while (!waiting && System.nanoTime() < deadline) {
        waiting =
            monitorDsl.fetchExists(
                DSL.table(DSL.name("pg_catalog", "pg_stat_activity")),
                DSL.field(DSL.name("wait_event_type"), String.class)
                    .eq("Lock")
                    .and(DSL.field(DSL.name("datname"), String.class).eq(DSL.currentCatalog())));
        if (!waiting) {
          TimeUnit.MILLISECONDS.sleep(10);
        }
      }
      sessionB.commit();
      return waiting;
    }
  }

  /** A の接続で、期待する版の行を更新する。 */
  private LockedRoot updateItem(final long expectedLockNo) {
    return writer.updateCheckingVersion(
        FIXTURE_ITEM,
        FIXTURE_ITEM.ITEM_ID.eq(ITEM_ID),
        expectedLockNo,
        set -> set.set(FIXTURE_ITEM.ITEM_NAME, "A"),
        ItemConflictException::new);
  }

  /** ユースケースの呼び出しの中として、pgm_cd を束縛して実行する。 */
  private static <T> T inUseCase(final Supplier<T> call) {
    return ScopedValue.where(PgmCdAspect.PGM_CD, PGM_CD).call(call::get);
  }
}
