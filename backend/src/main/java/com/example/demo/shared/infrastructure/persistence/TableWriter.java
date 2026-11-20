package com.example.demo.shared.infrastructure.persistence;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.concurrency.Versioned;
import com.example.demo.shared.failure.NotFoundException;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.Table;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 集約の INSERT と UPDATE を組み立てて実行する唯一の入口（ADR-054、docs/database/postgresql-concurrency-control.md）。
 *
 * <p>集約の形は、Repository が {@link AggregateTable} と {@link ChildTable}
 * で一度だけ宣言する。保存のたびに、ルートの列の値と、保存後にあるべき子の全行（{@link ChildRows}）を渡す。{@code Condition} も {@code Insert}
 * も受け取らず、ルートの条件、版の条件、子の外部キーの条件は、宣言から {@code TableWriter} が作る。
 *
 * <p>{@link #update} は、ルートの行を {@code 一意の列 AND lock_no = 期待値}
 * で更新して版を進める。ルートの行ロックの下で、子を差分（削除、更新、追加）で書く。{@link #insert} は、ルートを書いてから子を複数行の INSERT で書く。ルートの内部の主キーは
 * {@code RETURNING} で受けるため、Repository は読み直さない。
 *
 * <p>共通カラム、版の加算、件数の判定、{@code 55P03}（種類 {@code LOCK}）と {@code 23505}（種類 {@code UNIQUE}）の {@link
 * ConflictException} への変換は、すべてここで行う。変換は例外の型ではなく SQLSTATE で決める。どの入口も、読み取り専用ではない、開いているトランザクションの中で呼ぶ。
 *
 * <p>例外のメッセージには、テーブル名、ルートの一意の列の値、期待する版を入れ、SQL は入れない（docs/observability/conventions.md）。
 *
 * <p>ponytail: 上限：子の行の UPDATE は 1 行ずつ実行する。子が多い集約が出たら、バッチにして件数の配列で 1 件ずつを確かめる。
 */
// jOOQ の型を使って集約のルートの文を組み立てるため、結合するクラスの数の上限を外す。
@SuppressWarnings("PMD.CouplingBetweenObjects")
@Component
public class TableWriter {

  /** 楽観的ロックの版のカラム名。 */
  private static final String LOCK_NO = "lock_no";

  /** 一つの文で変える行数。 */
  private static final int ONE_ROW = 1;

  /** 版の最小値。INSERT が {@code lock_no = 1} で書く。 */
  private static final long FIRST_LOCK_NO = 1L;

  /** UPDATE と DELETE を実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 共通カラムの値を作る共通処理。 */
  private final CommonColumns commonColumns;

  /** 集約の子の行を書く。 */
  private final ChildWriter children;

  /** jOOQ のコンテキストと、共通カラムの値を作る共通処理を受け取る。 */
  public TableWriter(final DSLContext dsl, final CommonColumns commonColumns) {
    this.dsl = dsl;
    this.commonColumns = commonColumns;
    this.children = new ChildWriter(dsl, commonColumns);
  }

  /**
   * 子のない集約を INSERT する。共通カラムを書き、{@code lock_no = 1} にする。
   *
   * @param aggregate 子を宣言していない集約
   * @param key ルートの一意の列の値
   * @param rootColumns ルートの業務の列の値。ルートの一意の列と内部の主キーは書けない
   * @throws IllegalArgumentException 集約が子を宣言している場合、共通カラムなどの書けない列を渡した場合
   * @throws IllegalStateException トランザクションが開いていない、または読み取り専用の場合
   * @throws ConflictException 一意制約に違反した場合（種類は {@code UNIQUE}）、行ロックを {@code lock_timeout}
   *     までに取れない場合（種類は {@code LOCK}）。原因は持たない
   */
  public <R extends Record, K> void insert(
      final AggregateTable<R, K> aggregate,
      final K key,
      final Consumer<ColumnValues<R>> rootColumns) {
    if (!aggregate.children().isEmpty()) {
      throw new IllegalArgumentException(
          "children must be put: table=" + aggregate.table().getName());
    }
    insertRoot(aggregate, key, rootColumns);
  }

  /**
   * 集約を INSERT する。ルートを書き、子を複数行の INSERT で書く。
   *
   * @param aggregate 集約の宣言
   * @param key ルートの一意の列の値
   * @param rootColumns ルートの業務の列の値。ルートの一意の列と内部の主キーは書けない
   * @param childRows 宣言した子すべての行。空の子は空の一覧で渡す
   * @throws IllegalArgumentException 宣言した子を渡していない場合、宣言していない子を渡した場合、書けない列を渡した場合
   * @throws IllegalStateException トランザクションが開いていない、または読み取り専用の場合
   * @throws ConflictException 一意制約に違反した場合（種類は {@code UNIQUE}）、行ロックを {@code lock_timeout}
   *     までに取れない場合（種類は {@code LOCK}）。原因は持たない
   */
  public <R extends Record, K> void insert(
      final AggregateTable<R, K> aggregate,
      final K key,
      final Consumer<ColumnValues<R>> rootColumns,
      final Consumer<ChildRows<R>> childRows) {
    final ChildRows<R> rows = collectChildRows(aggregate, childRows);
    final long id = insertRoot(aggregate, key, rootColumns);
    for (final ChildTable<R, ?> child : aggregate.children()) {
      children.saveChild(child, id, rows, false);
    }
  }

  /**
   * 期待する版のルートを 1 行更新して版を期待値 + 1 にし、子を差分で書く。
   *
   * <p>ルートの SET は業務の列、{@code updated_*}、{@code lock_no = 期待値 + 1}、WHERE は {@code 一意の列 AND lock_no =
   * 期待値}
   * になる。業務の列が一つもなくても、版と更新のカラムを進める。子は、ルートの行ロックを取った後に、保存済みの自然キーを読み、なくなった子の削除、残る子の更新、新しい子の追加の順で書く。
   *
   * @param aggregate 集約の宣言
   * @param key ルートの一意の列の値
   * @param root 以前に読んだ集約ルート。{@link Versioned#lockNo} が期待する版になる
   * @param rootColumns ルートの業務の列の値
   * @param childRows 宣言した子すべての、保存後にあるべき行。渡した子の自然キーにない保存済みの子は削除する
   * @throws IllegalArgumentException 期待する版が 1 未満の場合、宣言した子を渡していない場合、宣言していない子を渡した場合、自然キーが null
   *     か重複する場合、書けない列を渡した場合
   * @throws IllegalStateException トランザクションが開いていない、または読み取り専用の場合、更新件数が 2 以上の場合
   * @throws NotFoundException 更新件数が 0 で、一意の列の行がない場合
   * @throws ConflictException 更新件数が 0 で一意の列の行がある場合（種類は {@code VERSION}）、一意制約に違反した場合（種類は {@code
   *     UNIQUE}）、行ロックを {@code lock_timeout} までに取れない場合（種類は {@code LOCK}）。原因は持たない
   */
  public <R extends Record, K> void update(
      final AggregateTable<R, K> aggregate,
      final K key,
      final Versioned root,
      final Consumer<ColumnValues<R>> rootColumns,
      final Consumer<ChildRows<R>> childRows) {
    requireTransaction();
    final long expectedLockNo = root.lockNo();
    final Table<R> table = aggregate.table();
    if (expectedLockNo < FIRST_LOCK_NO) {
      throw new IllegalArgumentException(
          "expectedLockNo must be 1 or greater: table="
              + table.getName()
              + ", expectedLockNo="
              + expectedLockNo);
    }
    final ChildRows<R> rows = collectChildRows(aggregate, childRows);
    final ColumnValues<R> values = collect(aggregate, rootColumns);
    final Field<Long> lockNo = CommonColumns.requiredField(table, LOCK_NO, Long.class);
    final Supplier<String> target = () -> target(table, aggregate.key(), key, expectedLockNo);
    final Result<Record1<Long>> updated =
        ConflictTranslator.executeOrConflict(
            () ->
                dsl.update(table)
                    .set(values.values())
                    .set(commonColumns.forUpdate(table))
                    .set(lockNo, Math.addExact(expectedLockNo, 1))
                    .where(aggregate.key().eq(key).and(lockNo.eq(expectedLockNo)))
                    .returningResult(aggregate.id())
                    .fetch(),
            target);
    requireOneRow(updated, aggregate, key, expectedLockNo);
    final long id = updated.getFirst().value1();
    for (final ChildTable<R, ?> child : aggregate.children()) {
      children.saveChild(child, id, rows, true);
    }
  }

  /** 更新したルートがちょうど 1 行であることを確かめ、そうでなければ NotFound か Conflict にする。 */
  private <R extends Record, K> void requireOneRow(
      final Result<Record1<Long>> updated,
      final AggregateTable<R, K> aggregate,
      final K key,
      final long expectedLockNo) {
    final Table<R> table = aggregate.table();
    final String target = target(table, aggregate.key(), key, expectedLockNo);
    if (updated.size() > ONE_ROW) {
      throw new IllegalStateException(
          "key condition matched " + updated.size() + " rows: " + target);
    }
    if (updated.isEmpty()) {
      if (!dsl.fetchExists(table, aggregate.key().eq(key))) {
        throw new NotFoundException("row not found: " + target);
      }
      throw new ConflictException(
          ConflictException.Kind.VERSION, "row was updated by another request: " + target);
    }
  }

  /** ルートを INSERT し、RETURNING で受けた内部の主キーを返す。 */
  private <R extends Record, K> long insertRoot(
      final AggregateTable<R, K> aggregate,
      final K key,
      final Consumer<ColumnValues<R>> rootColumns) {
    requireTransaction();
    final Table<R> table = aggregate.table();
    final ColumnValues<R> values = collect(aggregate, rootColumns);
    return ConflictTranslator.executeOrConflict(
        () ->
            dsl.insertInto(table)
                .set(aggregate.key(), key)
                .set(values.values())
                .set(commonColumns.forInsert(table))
                .returningResult(aggregate.id())
                .fetchSingle()
                .value1(),
        () -> "table=" + table.getName() + ", key=" + key);
  }

  /** 呼び出し側のラムダから、ルートの業務の列の値を集める。 */
  private static <R extends Record, K> ColumnValues<R> collect(
      final AggregateTable<R, K> aggregate, final Consumer<ColumnValues<R>> rootColumns) {
    final ColumnValues<R> values =
        new ColumnValues<>(aggregate.table(), Arrays.asList(aggregate.key(), aggregate.id()));
    rootColumns.accept(values);
    return values;
  }

  /** 呼び出し側のラムダから子の行を集め、宣言した子と過不足がなく、行に不備がないことを SQL の前に確かめる。 */
  private static <R extends Record, K> ChildRows<R> collectChildRows(
      final AggregateTable<R, K> aggregate, final Consumer<ChildRows<R>> childRows) {
    final ChildRows<R> rows = new ChildRows<>();
    childRows.accept(rows);
    if (rows.size() != aggregate.children().size()) {
      throw new IllegalArgumentException(
          "child rows must match the declared children: table=" + aggregate.table().getName());
    }
    for (final ChildTable<R, ?> child : aggregate.children()) {
      ChildWriter.desiredByKey(child, rows);
    }
    return rows;
  }

  /** 書き込みは、開いていて読み取り専用でないトランザクションの中でだけ行う。 */
  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("a read-write transaction is required to write");
    }
  }

  /** 例外のメッセージに入れる、テーブル名、ルートの一意の列の値、期待する版。 */
  private static String target(
      final Table<?> table, final Field<?> keyField, final Object key, final long expected) {
    return "table="
        + table.getName()
        + ", "
        + keyField.getName()
        + "="
        + key
        + ", expectedLockNo="
        + expected;
  }
}
