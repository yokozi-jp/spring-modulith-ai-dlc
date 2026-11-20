package com.example.demo.shared.infrastructure.persistence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import org.jooq.Field;
import org.jooq.Record;

/**
 * 保存後にあるべき、集約の子の全行（ADR-054）。
 *
 * <p>宣言した子ごとに {@link #put} で渡す。子がない集約でも、宣言した子は空の一覧で渡す。渡さない子があると {@link TableWriter} は {@link
 * IllegalArgumentException} を投げる。渡さないことを「変更なし」と読むと、次の保存で消えるべき子が残るため、渡し忘れを許さない。
 *
 * <p>行の不備（宣言していない子、自然キーが {@code null} か重複する、行ごとに書く列が違う）は、{@link #put} が、渡さない子は {@link TableWriter}
 * が、SQL を実行する前に {@link IllegalArgumentException} にする。
 *
 * @param <R> 集約ルートのテーブルの Record の型
 */
// 子の行の検査に jOOQ の型と標準のコレクションを使うため、結合するクラスの数の上限を外す。
@SuppressWarnings("PMD.CouplingBetweenObjects")
public final class ChildRows<R extends Record> {

  /** 子の宣言ごとの行。行は自然キーの値の組で引く。宣言は同一性で区別する。 */
  // 一つの保存の呼び出しの間だけ、一つのスレッドで使う。ChildTable は equals を持たないため、同一性で区別される。
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private final Map<ChildTable<R, ?>, Map<List<Object>, ? extends ColumnValues<?>>> rows =
      new LinkedHashMap<>();

  /** 集約が宣言した子。これ以外の子は渡せない。 */
  private final List<ChildTable<R, ?>> declared;

  /* package */ ChildRows(final List<ChildTable<R, ?>> declared) {
    this.declared = declared;
  }

  /**
   * 子の全行を渡す。
   *
   * @param child 子の宣言
   * @param items 子の行にする業務の値。空なら、その子の行をすべて消す
   * @param columns 業務の値から子の列の値を作る。自然キーの列も書く。親の外部キーと共通カラムは書けない
   * @throws IllegalArgumentException 宣言していない子か、同じ子を二度渡した場合、自然キーが {@code null} か重複する場合、行ごとに書く列が違う場合
   */
  public <C extends Record, T> ChildRows<R> put(
      final ChildTable<R, C> child,
      final Collection<? extends T> items,
      final BiConsumer<? super T, ColumnValues<C>> columns) {
    if (!declared.contains(child)) {
      throw new IllegalArgumentException(
          "child is not declared by the aggregate: table=" + child.table().getName());
    }
    final Map<List<Object>, ColumnValues<C>> byKey = keyedRows(child, items, columns);
    if (rows.putIfAbsent(child, byKey) != null) {
      throw new IllegalArgumentException(
          "child rows were put twice: table=" + child.table().getName());
    }
    return this;
  }

  /**
   * 宣言した子すべてを渡したことを確かめる。
   *
   * @throws IllegalArgumentException 渡していない子がある場合
   */
  /* package */ void requireAllPut() {
    for (final ChildTable<R, ?> child : declared) {
      if (!rows.containsKey(child)) {
        throw new IllegalArgumentException(
            "child rows were not put: table=" + child.table().getName());
      }
    }
  }

  /** 子の全行を、自然キーの値の組で引ける形で返す。 */
  @SuppressWarnings("unchecked")
  /* package */ <C extends Record> Map<List<Object>, ColumnValues<C>> rowsOf(
      final ChildTable<R, C> child) {
    return (Map<List<Object>, ColumnValues<C>>)
        Objects.requireNonNull(rows.get(child), "child rows were not put");
  }

  /** 子の行を作り、自然キーの値の組で引く形にする。行の順を保つ。 */
  private static <C extends Record, T> Map<List<Object>, ColumnValues<C>> keyedRows(
      final ChildTable<?, C> child,
      final Collection<? extends T> items,
      final BiConsumer<? super T, ColumnValues<C>> columns) {
    // 一つの保存の呼び出しの間だけ、一つのスレッドで使う。
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    final Map<List<Object>, ColumnValues<C>> byKey = new LinkedHashMap<>();
    Set<Field<?>> setColumns = Set.of();
    for (final T item : items) {
      final ColumnValues<C> row = newRow(child, columns, item);
      final Set<Field<?>> rowColumns = Set.copyOf(row.values().keySet());
      if (byKey.isEmpty()) {
        setColumns = rowColumns;
      } else if (!rowColumns.equals(setColumns)) {
        throw new IllegalArgumentException(
            "child rows must set the same columns: table=" + child.table().getName());
      }
      final List<Object> naturalKey = child.naturalKeyOf(row);
      if (byKey.putIfAbsent(naturalKey, row) != null) {
        throw new IllegalArgumentException(
            "natural key is duplicated: table=" + child.table().getName() + ", key=" + naturalKey);
      }
    }
    return byKey;
  }

  private static <C extends Record, T> ColumnValues<C> newRow(
      final ChildTable<?, C> child,
      final BiConsumer<? super T, ColumnValues<C>> columns,
      final T item) {
    final ColumnValues<C> row = new ColumnValues<>(child.table(), List.of(child.parentKey()));
    columns.accept(item, row);
    return row;
  }
}
