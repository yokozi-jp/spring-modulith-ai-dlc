package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.error.ResourceConflictException;
import com.example.demo.error.ResourceNotFoundException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jooq.autoconfigure.ExceptionTranslatorExecuteListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 楽観的ロックが、行をロックしてから lock_no を比較し、結果に応じて更新するか例外にすることを検証する。 */
class OptimisticLockTest {

  /** 固定した現在時刻。 */
  private static final Instant NOW = Instant.parse("2026-10-03T01:02:03.123456Z");

  /** 現在のスパンの trace ID。 */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** DB に保存されている lock_no。 */
  private static final long STORED_LOCK_NO = 6L;

  /** PostgreSQL の lock_not_available。 */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  /** 発行された SQL とバインド値。 */
  private final List<MockExecuteContext> executed = new ArrayList<>();

  @AfterEach
  void endTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  @DisplayName("lock_no が一致すれば、FOR UPDATE NOWAIT でロックした後に業務と更新のカラム、lock_no + 1 を更新する")
  void updatesWhenLockNoMatches() {
    updateItem(optimisticLock(Responses.storedLockNo(STORED_LOCK_NO), false), STORED_LOCK_NO);

    assertThat(executed).as("発行した文").hasSize(2);
    assertThat(executed.getFirst().sql()).as("ロックの SQL").endsWith("for update nowait");
    final MockExecuteContext update = executed.get(1);
    assertThat(update.sql())
        .as("更新の SQL")
        .startsWith("update")
        .contains(
            "\"item_name\" = ?",
            "\"updated_at\" = cast(? as timestamp with time zone)",
            "\"updated_tx_id\" = ?")
        .contains("\"lock_no\" = (\"fixture\".\"t_fixture_item\".\"lock_no\" + ?)");
    assertThat(update.bindings())
        .as("更新のバインド値")
        .containsExactly(
            "renamed",
            // jOOQ は PostgreSQL の timestamptz を、UTC の文字列としてバインドする。
            "2026-10-03 01:02:03.123456+00:00",
            "shared.OptimisticLockTest",
            "shared.OptimisticLockTest",
            TRACE_ID,
            1,
            10L);
  }

  @Test
  @DisplayName("lock_no が一致しなければ競合の例外にし、更新しない")
  void rejectsMismatchedLockNo() {
    final OptimisticLock lock = optimisticLock(Responses.storedLockNo(STORED_LOCK_NO), false);

    assertThatThrownBy(() -> updateItem(lock, STORED_LOCK_NO - 1))
        .isInstanceOf(ResourceConflictException.class)
        .hasMessage("lock_no mismatch: table=t_fixture_item");
    assertThat(executed).as("ロックだけを発行し、更新しないこと").hasSize(1);
  }

  @Test
  @DisplayName("行がなければ未検出の例外にする")
  void rejectsMissingRow() {
    final OptimisticLock lock = optimisticLock(Responses.storedLockNo(), false);

    assertThatThrownBy(() -> updateItem(lock, STORED_LOCK_NO))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessage("row not found: table=t_fixture_item");
  }

  @Test
  @DisplayName("NOWAIT でロックを取れなければ（SQLSTATE 55P03）、Spring Boot の例外変換の有無によらず競合の例外にする")
  void lockNotAvailableIsConflict() {
    final OptimisticLock jooqOnly = optimisticLock(Responses.failure(LOCK_NOT_AVAILABLE), false);
    final OptimisticLock translated = optimisticLock(Responses.failure(LOCK_NOT_AVAILABLE), true);

    assertThatThrownBy(() -> updateItem(jooqOnly, STORED_LOCK_NO))
        .as("jOOQ の例外のまま届く場合")
        .isInstanceOf(ResourceConflictException.class)
        .hasMessage("lock not available: table=t_fixture_item")
        .hasCauseInstanceOf(DataAccessException.class)
        .hasRootCauseInstanceOf(SQLException.class);
    assertThatThrownBy(() -> updateItem(translated, STORED_LOCK_NO))
        .as("Spring の例外へ変換された場合")
        .isInstanceOf(ResourceConflictException.class)
        .hasCauseInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @Test
  @DisplayName("ロックの取得以外の DB エラーは、変換せずにそのまま投げる")
  void otherDatabaseErrorIsRethrown() {
    final OptimisticLock lock = optimisticLock(Responses.failure("42P01"), false);

    assertThatThrownBy(() -> updateItem(lock, STORED_LOCK_NO))
        .isInstanceOf(DataAccessException.class)
        .isNotInstanceOf(ResourceConflictException.class);
  }

  @Test
  @DisplayName("トランザクションの外で呼ばれたら、ロックが守れないため例外にする")
  void requiresActiveTransaction() {
    final OptimisticLock lock = optimisticLock(Responses.storedLockNo(STORED_LOCK_NO), false);
    TransactionSynchronizationManager.setActualTransactionActive(false);

    assertThatThrownBy(() -> updateItem(lock, STORED_LOCK_NO))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active transaction");
    assertThat(executed).as("SQL を発行しないこと").isEmpty();
  }

  private static void updateItem(final OptimisticLock lock, final long clientLockNo) {
    lock.update(
        FIXTURE_ITEM,
        FIXTURE_ITEM.ITEM_ID.eq(10L),
        clientLockNo,
        Map.of(FIXTURE_ITEM.ITEM_NAME, "renamed"),
        OptimisticLockTest.class);
  }

  /** 発行した文を記録する MockConnection で、トランザクションの中の楽観的ロックを作る。 */
  private OptimisticLock optimisticLock(
      final MockDataProvider provider, final boolean springTranslation) {
    final DefaultConfiguration configuration = new DefaultConfiguration();
    configuration.set(
        new MockConnection(
            context -> {
              executed.add(context);
              return provider.execute(context);
            }));
    configuration.set(SQLDialect.POSTGRES);
    if (springTranslation) {
      // アプリケーションと同じ、Spring Boot の jOOQ の例外変換を通す。
      configuration.set(ExceptionTranslatorExecuteListener.DEFAULT);
    }
    TransactionSynchronizationManager.setActualTransactionActive(true);
    return new OptimisticLock(
        DSL.using(configuration),
        new CommonColumns(Clock.fixed(NOW, ZoneOffset.UTC), TracerStubs.withTraceId(TRACE_ID)));
  }

  /** MockConnection が返す DB の応答。 */
  private static final class Responses {

    /** ロックの SELECT には保存された lock_no の行を返し、UPDATE には 1 件の更新を返す。 */
    private static MockDataProvider storedLockNo(final long... lockNos) {
      final DSLContext builder = DSL.using(SQLDialect.POSTGRES);
      final Result<Record1<Long>> rows = builder.newResult(FIXTURE_ITEM.LOCK_NO);
      for (final long lockNo : lockNos) {
        rows.add(builder.newRecord(FIXTURE_ITEM.LOCK_NO).values(lockNo));
      }
      return context ->
          context.sql().startsWith("select")
              ? new MockResult[] {new MockResult(rows.size(), rows)}
              : new MockResult[] {new MockResult(1)};
    }

    /** すべての文を、指定した SQLSTATE のエラーにする。 */
    private static MockDataProvider failure(final String sqlState) {
      return context -> {
        throw new SQLException("database error", sqlState);
      };
    }
  }
}
