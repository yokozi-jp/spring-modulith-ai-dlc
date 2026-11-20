package com.example.demo.shared.infrastructure.persistence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.jooq.Field;
import org.jooq.Record;

/**
 * 保存後にあるべき、集約の子の全行（ADR-054）。
 *
 * <p>宣言した子ごとに {@link #put} で渡す。子がない集約でも、宣言した子は空の一覧で渡す。渡さない子があると {@link TableWriter} は {@link
 * IllegalArgumentException} を投げる。渡さないことを「変更なし」と読むと、次の保存で消えるべき子が残るため、渡し忘れを許さない。
 *
 * <p>行の不備（自然キーが {@code null} か重複する、行ごとに書く列が違う）は、{@link #put} が SQL を実行する前に {@link
 * IllegalArgumentException} にする。
 *
 * @param <R> 集約ルートのテーブルの Record の型
 */
public final class ChildRows<R extends Record> {

  /** 子の宣言ごとの行。行は自然キーの値の組で引く。宣言は同一性で区別する。 */
  // 一つの保存の呼び出しの間だけ、一つのスレッドで使う。ChildTable は equals を持たないため、同一性で区別される。
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private final Map<ChildTable<R, ?>, Map<List<Object>, ? extends ColumnValues<?>>> rows =
      new LinkedHashMap<>();

  /* package */ ChildRows() {
    // 保存の呼び出しが空の状態から作る。
  }

  /**
   * 子の全行を渡す。
   *
   * @param child 子の宣言
   * @param items 子の行にする業務の値。空なら、その子の行をすべて消す
   * @param columns 業務の値から子の列の値を作る。自然キーの列も書く。親の外部キーと共通カラムは書けない
   * @throws IllegalArgumentException 同じ子を二度渡した場合、自然キーが {@code null} か重複する場合、行ごとに書く列が違う場合
   */
  public <C extends Record, T> ChildRows<R> put(
      final ChildTable<R, C> child,
      final Collection<? extends T> items,
      final BiConsumer<? super T, ColumnValues<C>> columns) {
    // 一つの保存の呼び出しの間だけ、一つのスレッドで使う。行の順を保つ。
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    final Map<List<Object>, ColumnValues<C>> byKey = new LinkedHashMap<>();
    List<Field<?>> setColumns = List.of();
    for (final T item : items) {
      final ColumnValues<C> row = newRow(child, columns, item);
      final List<Field<?>> rowColumns = List.copyOf(row.values().keySet());
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
    if (rows.putIfAbsent(child, byKey) != null) {
      throw new IllegalArgumentException(
          "child rows were put twice: table=" + child.table().getName());
    }
    return this;
  }

  /** 渡した子の数を返す。宣言していない子を渡していないかの確認に使う。 */
  /* package */ int size() {
    return rows.size();
  }

  /**
   * 子の全行を、自然キーの値の組で引ける形で返す。
   *
   * @throws IllegalArgumentException 渡していない場合
   */
  @SuppressWarnings("unchecked")
  /* package */ <C extends Record> Map<List<Object>, ColumnValues<C>> rowsOf(
      final ChildTable<R, C> child) {
    final Map<List<Object>, ? extends ColumnValues<?>> found = rows.get(child);
    if (found == null) {
      throw new IllegalArgumentException(
          "child rows were not put: table=" + child.table().getName());
    }
    return (Map<List<Object>, ColumnValues<C>>) found;
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
