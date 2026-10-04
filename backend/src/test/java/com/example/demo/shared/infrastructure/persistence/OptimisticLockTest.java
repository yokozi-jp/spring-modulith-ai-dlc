package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.SQLDataType;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 楽観的ロックの更新件数の判定が、0 件のときだけ行の有無を確かめ、件数に応じて例外にすることを検証する。 */
class OptimisticLockTest {

  /** 0 件のときの例外のメッセージが指す対象。 */
  private static final String TARGET =
      "table=t_fixture_item, where=\"fixture\".\"t_fixture_item\".\"item_id\" = 10";

  /** 発行された SQL。 */
  private final List<MockExecuteContext> executed = new ArrayList<>();

  @Test
  @DisplayName("1 件を更新したら、SQL を発行せずに戻る")
  void oneRowUpdatedPasses() {
    requireUpdated(optimisticLock(true), 1);

    assertThat(executed).as("発行した文").isEmpty();
  }

  @Test
  @DisplayName("0 件で行があれば、渡された関数の競合の例外にする")
  void zeroRowsWithExistingRowIsConflict() {
    final OptimisticLock lock = optimisticLock(true);

    assertThatThrownBy(() -> requireUpdated(lock, 0))
        .isInstanceOf(FixtureConflictException.class)
        .hasMessage("row was updated by another request: " + TARGET);
    assertThat(executed).as("発行した文").hasSize(1);
    assertThat(executed.getFirst().sql())
        .as("行の有無を確かめる SQL")
        .startsWith("select exists")
        .contains("\"fixture\".\"t_fixture_item\"");
  }

  @Test
  @DisplayName("0 件で行がなければ、未検出の例外にする")
  void zeroRowsWithoutRowIsNotFound() {
    final OptimisticLock lock = optimisticLock(false);

    assertThatThrownBy(() -> requireUpdated(lock, 0))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessage("row not found: " + TARGET);
  }

  @Test
  @DisplayName("2 件以上を更新したら、主キーの条件の誤りとして例外にし、SQL を発行しない")
  void multipleRowsUpdatedIsRejected() {
    final OptimisticLock lock = optimisticLock(true);

    assertThatThrownBy(() -> requireUpdated(lock, 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("primary key condition updated 2 rows: " + TARGET);
    assertThat(executed).as("発行した文").isEmpty();
  }

  private static void requireUpdated(final OptimisticLock lock, final int updated) {
    lock.requireUpdated(
        updated, FIXTURE_ITEM, FIXTURE_ITEM.ITEM_ID.eq(10L), FixtureConflictException::new);
  }

  /** 行の有無を確かめる SELECT に、指定した結果を返す MockConnection で作る。 */
  private OptimisticLock optimisticLock(final boolean rowExists) {
    final DSLContext builder = DSL.using(SQLDialect.POSTGRES);
    final Field<Boolean> exists = DSL.field(DSL.name("exists"), SQLDataType.BOOLEAN);
    final Result<Record1<Boolean>> rows = builder.newResult(exists);
    rows.add(builder.newRecord(exists).values(rowExists));
    final DefaultConfiguration configuration = new DefaultConfiguration();
    configuration.set(
        new MockConnection(
            context -> {
              executed.add(context);
              return new MockResult[] {new MockResult(1, rows)};
            }));
    configuration.set(SQLDialect.POSTGRES);
    return new OptimisticLock(DSL.using(configuration));
  }

  /** 集約の競合の例外の代わりに、呼び出し側が渡す例外。 */
  /* package */ static final class FixtureConflictException extends RuntimeException {

    /** 直列化の版。 */
    private static final long serialVersionUID = 1L;

    /* package */ FixtureConflictException(final String message) {
      super(message);
    }
  }
}
