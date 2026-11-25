package com.example.demo.shared.persistence;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.concurrency.Versioned;
import com.example.demo.shared.failure.NotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.Table;
import org.springframework.stereotype.Component;

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
 */
// jOOQ の型を使って集約のルートの文を組み立てるため、結合するクラスの数の上限を外す。
@SuppressWarnings("PMD.CouplingBetweenObjects")
@Component
public class TableWriter {

  /** 一つの文や宣言に合う行の数の、あるべき値。 */
  private static final int ONE_ROW = 1;

  /** INSERT と UPDATE を実行する jOOQ のコンテキスト。 */
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
   * 集約を INSERT する。ルートを書き、子を複数行の INSERT で書く。
   *
   * <p>子を宣言していない集約は、{@code childRows} を省く。
   *
   * @param aggregate 集約の宣言
   * @param root 新しい集約ルート。ルートの一意の列の値は、宣言の {@code keyOf} で集約ルートから取る
   * @param rootColumns ルートの業務の列の値。ルートの一意の列と内部の主キーは書けない
   * @param childRows 宣言した子すべての行（{@link ChildTable#rows}）。空の子は空の一覧で作る
   * @throws IllegalArgumentException 宣言した子を渡していない場合、宣言していない子か同じ子を二度渡した場合、書けない列を渡した場合
   * @throws IllegalStateException トランザクションが開いていない、または読み取り専用の場合
   * @throws ConflictException 一意制約に違反した場合（種類は {@code UNIQUE}）、行ロックを {@code lock_timeout}
   *     までに取れない場合（種類は {@code LOCK}）。原因は持たない
   */
  public <R extends Record, K, A extends Versioned> void insert(
      final AggregateTable<R, K, A> aggregate,
      final A root,
      final Consumer<ColumnValues<R>> rootColumns,
      final ChildRows<?>... childRows) {
    requireTransaction(dsl);
    final List<ChildRows<?>> rows = inDeclaredOrder(aggregate, Arrays.asList(childRows));
    final long id = insertRoot(aggregate, aggregate.keyOf(root), rootColumns);
    for (final ChildRows<?> childRow : rows) {
      children.insertAll(id, childRow);
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
   * @param root 以前に読んだ集約ルート。ルートの一意の列の値は宣言の {@code keyOf} で、期待する版は {@link Versioned#lockNo}
   *     で、この集約ルートから取る
   * @param rootColumns ルートの業務の列の値
   * @param childRows 宣言した子すべての、保存後にあるべき行（{@link ChildTable#rows}）。渡した子の自然キーにない保存済みの子は削除する
   * @throws IllegalArgumentException 期待する版が 1 未満の場合、宣言した子を渡していない場合、宣言していない子か同じ子を二度渡した場合
   * @throws IllegalStateException トランザクションが開いていない、または読み取り専用の場合、更新件数が 2 以上の場合
   * @throws NotFoundException 更新件数が 0 で、一意の列の行がない場合
   * @throws ConflictException 更新件数が 0 で一意の列の行がある場合（種類は {@code VERSION}）、一意制約に違反した場合（種類は {@code
   *     UNIQUE}）、行ロックを {@code lock_timeout} までに取れない場合（種類は {@code LOCK}）。原因は持たない
   */
  public <R extends Record, K, A extends Versioned> void update(
      final AggregateTable<R, K, A> aggregate,
      final A root,
      final Consumer<ColumnValues<R>> rootColumns,
      final ChildRows<?>... childRows) {
    requireTransaction(dsl);
    final K key = aggregate.keyOf(root);
    final long expectedLockNo = root.lockNo();
    final Table<R> table = aggregate.table();
    if (expectedLockNo < Versioned.FIRST_LOCK_NO) {
      throw new IllegalArgumentException(
          "expectedLockNo must be 1 or greater: table="
              + table.getName()
              + ", expectedLockNo="
              + expectedLockNo);
    }
    final List<ChildRows<?>> rows = inDeclaredOrder(aggregate, Arrays.asList(childRows));
    final ColumnValues<R> values = collect(aggregate, rootColumns);
    final Field<Long> lockNo =
        CommonColumns.requiredField(table, CommonColumns.LOCK_NO, Long.class);
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
    requireOneRow(updated, aggregate, key, target);
    final long id = updated.getFirst().value1();
    for (final ChildRows<?> childRow : rows) {
      children.replace(id, childRow);
    }
  }

  /** 更新したルートがちょうど 1 行であることを確かめ、そうでなければ NotFound か Conflict にする。 */
  private <R extends Record, K> void requireOneRow(
      final Result<Record1<Long>> updated,
      final AggregateTable<R, K, ?> aggregate,
      final K key,
      final Supplier<String> target) {
    if (updated.size() > ONE_ROW) {
      throw new IllegalStateException(
          "key condition matched " + updated.size() + " rows: " + target.get());
    }
    if (updated.isEmpty()) {
      if (!dsl.fetchExists(aggregate.table(), aggregate.key().eq(key))) {
        throw new NotFoundException("row not found: " + target.get());
      }
      throw new ConflictException(
          ConflictException.Kind.VERSION, "row was updated by another request: " + target.get());
    }
  }

  /** ルートを INSERT し、RETURNING で受けた内部の主キーを返す。 */
  private <R extends Record, K> long insertRoot(
      final AggregateTable<R, K, ?> aggregate,
      final K key,
      final Consumer<ColumnValues<R>> rootColumns) {
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
      final AggregateTable<R, K, ?> aggregate, final Consumer<ColumnValues<R>> rootColumns) {
    final ColumnValues<R> values =
        new ColumnValues<>(aggregate.table(), Arrays.asList(aggregate.key(), aggregate.id()));
    rootColumns.accept(values);
    return values;
  }

  /** 渡された子の行を宣言の順に並べ、宣言した子と過不足がないことを SQL の前に確かめる。 */
  private static List<ChildRows<?>> inDeclaredOrder(
      final AggregateTable<?, ?, ?> aggregate, final List<ChildRows<?>> given) {
    for (final ChildRows<?> rows : given) {
      if (!aggregate.children().contains(rows.child())) {
        throw new IllegalArgumentException(
            "child is not declared by the aggregate: table=" + rows.child().table().getName());
      }
    }
    final List<ChildRows<?>> ordered = new ArrayList<>();
    for (final ChildTable<?> child : aggregate.children()) {
      final List<ChildRows<?>> matched =
          given.stream().filter(rows -> child.equals(rows.child())).toList();
      if (matched.isEmpty()) {
        throw new IllegalArgumentException(
            "child rows were not put: table=" + child.table().getName());
      }
      if (matched.size() > ONE_ROW) {
        throw new IllegalArgumentException(
            "child rows were put twice: table=" + child.table().getName());
      }
      ordered.add(matched.getFirst());
    }
    return ordered;
  }

  /**
   * 書き込みは、開いていて読み取り専用でないトランザクションの中でだけ行う。
   *
   * <p>Spring の {@code @Transactional} と、jOOQ の {@code transactionResult}
   * のどちらで開いたトランザクションも、接続が自動コミットではないことで見分ける。{@link IdempotencyKeyWriter} も使う。
   */
  /* package */ static void requireTransaction(final DSLContext dsl) {
    final boolean writable =
        dsl.connectionResult(connection -> !connection.getAutoCommit() && !connection.isReadOnly());
    if (!writable) {
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
