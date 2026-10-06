package com.example.demo.shared.infrastructure.persistence;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.failure.NotFoundException;
import com.google.errorprone.annotations.CheckReturnValue;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Insert;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 業務テーブルの UPDATE と DELETE を組み立てて実行する唯一の入口で、集約ルートの INSERT
 * の入口（ADR-054、docs/database/postgresql-concurrency-control.md）。
 *
 * <p>版の条件、{@code lock_no} の設定、更新のカラム、実行、件数の判定、{@code 55P03} と {@code 23505} の変換をここに集める。Repository
 * が書くのは、{@link ColumnValues} に渡す業務の列の値だけである。
 *
 * <p>入口の選び分けは、呼び出し側が期待する版（以前に読んだ集約ルートの {@code lock_no}）を持っているかだけで決まる。
 *
 * <ul>
 *   <li>持っている：{@link #updateCheckingVersion} と {@link #deleteCheckingVersion}。子の行は戻り値の {@link
 *       LockedRoot} と {@link DeletedRoot} で書く。
 *   <li>持っていない：{@link #updateWhere} と {@link #deleteWhere}。件数の意味は呼び出し側が決める。
 * </ul>
 *
 * <p>どの UPDATE も {@code lock_no} を進める。集約ルートの INSERT は {@link #insert} で実行する。子の行の INSERT
 * はここを通さず、{@code dsl.batch} と {@link CommonColumns#forInsert} で書く。
 *
 * <p>例外のメッセージには、テーブル名、主キーの条件のバインド値、期待する版を入れ、SQL は入れない（docs/observability/conventions.md）。
 */
// 業務テーブルの UPDATE と DELETE の入口、件数の判定、ロック待ちの変換を一つのクラスに集めるため（ADR-054）、メソッドと依存する型の数の上限を外す。
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects"})
@Component
public class TableWriter {

  /** 楽観的ロックの版のカラム名。 */
  private static final String LOCK_NO = "lock_no";

  /** 主キーの条件で更新、削除できる行数。 */
  private static final int ONE_ROW = 1;

  /** 版の最小値。INSERT が {@code lock_no = 1} で書く。 */
  private static final long FIRST_LOCK_NO = 1L;

  /** UPDATE と DELETE を実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 更新のカラムの値を作る共通処理。 */
  private final CommonColumns commonColumns;

  /** jOOQ のコンテキストと、更新のカラムの値を作る共通処理を受け取る。 */
  public TableWriter(final DSLContext dsl, final CommonColumns commonColumns) {
    this.dsl = dsl;
    this.commonColumns = commonColumns;
  }

  /**
   * 期待する版の行を 1 行更新し、版を期待値 + 1 にする。
   *
   * <p>SET は業務の列、{@code updated_*}、{@code lock_no = 期待値 + 1}、WHERE は {@code 主キーの条件 AND lock_no =
   * 期待値} になる。業務の列が一つもなくても、ルートの行の版と更新のカラムを進める。
   *
   * @param table 集約ルートのテーブル
   * @param byPrimaryKey 主キーで 1 行を特定する条件。{@code lock_no} の条件は含めない
   * @param expectedLockNo 以前に読んだ集約ルートの版
   * @param businessColumns 業務の列の値を {@link ColumnValues} に登録する
   * @return 子の行を更新、削除する {@link LockedRoot}
   * @throws IllegalArgumentException {@code expectedLockNo} が 1 未満の場合、テーブルに {@code lock_no}（{@code
   *     Long}）がない場合、業務の列に共通カラムを渡した場合
   * @throws NotFoundException 更新件数が 0 で、主キーの行がない場合
   * @throws ConflictException 更新件数が 0 で主キーの行がある場合、一意制約に違反した場合（{@link DuplicateKeyException}
   *     を原因に持つ）、行ロックを {@code lock_timeout} までに取れない場合（{@link CannotAcquireLockException} を原因に持つ）
   * @throws IllegalStateException 更新件数が 2 以上の場合。主キーの条件が 1 行を特定していない
   */
  public <R extends Record> LockedRoot updateCheckingVersion(
      final Table<R> table,
      final Condition byPrimaryKey,
      final long expectedLockNo,
      final Consumer<ColumnValues<R>> businessColumns) {
    final Field<Long> lockNo = versionOf(table, expectedLockNo);
    final Query update =
        dsl.update(table)
            .set(collect(table, businessColumns).values())
            .set(commonColumns.forUpdate(table))
            .set(lockNo, Math.addExact(expectedLockNo, 1))
            .where(byPrimaryKey.and(lockNo.eq(expectedLockNo)));
    requireOneRow(update, table, byPrimaryKey, expectedLockNo);
    return new LockedRoot(this);
  }

  /**
   * 期待する版の行を 1 行削除する。
   *
   * <p>WHERE は {@code 主キーの条件 AND lock_no = 期待値} になる。子の行は戻り値から削除する。
   *
   * @param table 集約ルートのテーブル
   * @param byPrimaryKey 主キーで 1 行を特定する条件。{@code lock_no} の条件は含めない
   * @param expectedLockNo 以前に読んだ集約ルートの版
   * @return 子の行を削除する {@link DeletedRoot}
   * @throws IllegalArgumentException {@code expectedLockNo} が 1 未満の場合、テーブルに {@code lock_no}（{@code
   *     Long}）がない場合
   * @throws NotFoundException 削除件数が 0 で、主キーの行がない場合
   * @throws ConflictException 削除件数が 0 で主キーの行がある場合、一意制約に違反した場合（{@link DuplicateKeyException}
   *     を原因に持つ）、行ロックを {@code lock_timeout} までに取れない場合（{@link CannotAcquireLockException} を原因に持つ）
   * @throws IllegalStateException 削除件数が 2 以上の場合。主キーの条件が 1 行を特定していない
   */
  public <R extends Record> DeletedRoot deleteCheckingVersion(
      final Table<R> table, final Condition byPrimaryKey, final long expectedLockNo) {
    final Field<Long> lockNo = versionOf(table, expectedLockNo);
    requireOneRow(
        dsl.deleteFrom(table).where(byPrimaryKey.and(lockNo.eq(expectedLockNo))),
        table,
        byPrimaryKey,
        expectedLockNo);
    return new DeletedRoot(this);
  }

  /**
   * 版を比べずに条件に合う行を更新し、版を 1 進めて件数を返す。
   *
   * <p>SET は業務の列、{@code updated_*}、{@code lock_no = lock_no + 1} になる。期待する版を持つ書き込みには使わない。
   *
   * @param table 更新するテーブル
   * @param where 更新する行の条件
   * @param businessColumns 業務の列の値を {@link ColumnValues} に登録する。一つ以上必要
   * @return 更新件数。0 件の意味（在庫不足など）は呼び出し側が決める
   * @throws IllegalArgumentException テーブルに {@code lock_no}（{@code
   *     Long}）がない場合、業務の列が一つもない場合、業務の列に共通カラムを渡した場合
   * @throws CannotAcquireLockException 行ロックを {@code lock_timeout} までに取れない場合。変換せずに投げる
   * @throws DuplicateKeyException 一意制約に違反した場合（{@code 23505}）。変換せずに投げる
   */
  @CheckReturnValue
  public <R extends Record> int updateWhere(
      final Table<R> table,
      final Condition where,
      final Consumer<ColumnValues<R>> businessColumns) {
    final Field<Long> lockNo = CommonColumns.requiredField(table, LOCK_NO, Long.class);
    final ColumnValues<R> values = collect(table, businessColumns);
    if (values.isEmpty()) {
      throw new IllegalArgumentException("no business column to update: table=" + table.getName());
    }
    return dsl.update(table)
        .set(values.values())
        .set(commonColumns.forUpdate(table))
        .set(lockNo, lockNo.plus(1))
        .where(where)
        .execute();
  }

  /**
   * Repository が組み立てた INSERT を実行する。
   *
   * <p>共通カラムは呼び出し側が {@link CommonColumns#forInsert} で設定する。
   *
   * <p>ponytail: 上限：子の行の dsl.batch の INSERT は変換しない。子のテーブルに業務の一意インデックスを足したら、batch を受ける入口を足す。
   *
   * @param insert {@code dsl.insertInto(...).set(...).set(commonColumns.forInsert(...))} で組み立てた 1
   *     行の INSERT
   * @throws ConflictException 一意制約に違反した場合（{@link DuplicateKeyException} を原因に持つ）と、行ロックを {@code
   *     lock_timeout} までに取れない場合（{@link CannotAcquireLockException} を原因に持つ）
   */
  public void insert(final Insert<?> insert) {
    // テーブル名は Insert の公開 API で取れないため入れない。テーブル、制約名、キーは原因の例外のメッセージにある。
    executeOrConflict(insert::execute, () -> "insert");
  }

  /**
   * 子の行を条件で更新し、版を 1 進めて件数を返す。{@link LockedRoot#updateChild} が使う。
   *
   * @throws ConflictException 一意制約に違反した場合（{@link DuplicateKeyException} を原因に持つ）と、行ロックを {@code
   *     lock_timeout} までに取れない場合（{@link CannotAcquireLockException} を原因に持つ）
   */
  @CheckReturnValue
  /* package */ <R extends Record> int updateChildRows(
      final Table<R> table,
      final Condition byPrimaryKey,
      final Consumer<ColumnValues<R>> businessColumns) {
    return executeOrConflict(
        () -> updateWhere(table, byPrimaryKey, businessColumns),
        () -> childTarget(table, byPrimaryKey));
  }

  /**
   * 版を比べずに条件に合う行を削除し、件数を返す。
   *
   * @param table 削除するテーブル
   * @param where 削除する行の条件
   * @return 削除件数。件数の意味は呼び出し側が決める
   * @throws CannotAcquireLockException 行ロックを {@code lock_timeout} までに取れない場合。変換せずに投げる
   */
  @CheckReturnValue
  public int deleteWhere(final Table<?> table, final Condition where) {
    return dsl.deleteFrom(table).where(where).execute();
  }

  /**
   * 子の行を条件で削除する。{@link LockedRoot} と {@link DeletedRoot} が使う。
   *
   * @throws ConflictException 一意制約に違反した場合（{@link DuplicateKeyException} を原因に持つ）と、行ロックを {@code
   *     lock_timeout} までに取れない場合（{@link CannotAcquireLockException} を原因に持つ）
   */
  /* package */ void deleteRows(final Table<?> table, final Condition where) {
    executeOrConflict(
        () -> dsl.deleteFrom(table).where(where).execute(), () -> childTarget(table, where));
  }

  /** 期待する版を確かめ、テーブルの {@code lock_no} を返す。SQL を実行する前に失敗させる。 */
  private static Field<Long> versionOf(final Table<?> table, final long expectedLockNo) {
    if (expectedLockNo < FIRST_LOCK_NO) {
      throw new IllegalArgumentException(
          "expectedLockNo must be 1 or greater: table="
              + table.getName()
              + ", expectedLockNo="
              + expectedLockNo);
    }
    return CommonColumns.requiredField(table, LOCK_NO, Long.class);
  }

  /** 呼び出し側のラムダから業務の列の値を集める。 */
  private static <R extends Record> ColumnValues<R> collect(
      final Table<R> table, final Consumer<ColumnValues<R>> businessColumns) {
    final ColumnValues<R> values = new ColumnValues<>(table);
    businessColumns.accept(values);
    return values;
  }

  /** 主キーと版を条件にした文を実行し、ちょうど 1 行を変えたことを確かめる。 */
  private void requireOneRow(
      final Query query,
      final Table<?> table,
      final Condition byPrimaryKey,
      final long expectedLockNo) {
    final int changed =
        executeOrConflict(query::execute, () -> target(table, byPrimaryKey, expectedLockNo));
    if (changed == ONE_ROW) {
      return;
    }
    final String target = target(table, byPrimaryKey, expectedLockNo);
    if (changed > ONE_ROW) {
      throw new IllegalStateException(
          "primary key condition matched " + changed + " rows: " + target);
    }
    if (!dsl.fetchExists(table, byPrimaryKey)) {
      throw new NotFoundException("row not found: " + target);
    }
    throw new ConflictException("row was updated by another request: " + target);
  }

  /**
   * 文を実行して件数を返し、{@code 55P03} の {@link CannotAcquireLockException} と {@code 23505} の {@link
   * DuplicateKeyException} を、それを原因に持つ {@link ConflictException} に変える。
   */
  private static int executeOrConflict(final IntSupplier statement, final Supplier<String> target) {
    try {
      return statement.getAsInt();
    } catch (final CannotAcquireLockException e) {
      throw new ConflictException("row is locked by another request: " + target.get(), e);
    } catch (final DuplicateKeyException e) {
      throw new ConflictException("unique key already exists: " + target.get(), e);
    }
  }

  /** 例外のメッセージに入れる、テーブル名と条件のバインド値。SQL を入れず、キーの値だけを入れる。{@link LockedRoot} も使う。 */
  /* package */ String childTarget(final Table<?> table, final Condition condition) {
    return "table=" + table.getName() + ", key=" + dsl.extractBindValues(condition);
  }

  /** 例外のメッセージに入れる、テーブル名、主キーの条件のバインド値、期待する版。 */
  private String target(final Table<?> table, final Condition byPrimaryKey, final long expected) {
    return childTarget(table, byPrimaryKey) + ", expectedLockNo=" + expected;
  }
}
