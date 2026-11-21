package com.example.demo.shared.infrastructure.persistence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.jooq.Field;
import org.jooq.Record;

/**
 * 保存後にあるべき、集約の子一種類の全行（ADR-054）。{@link ChildTable#rows} が作り、{@link TableWriter} の入口に渡す。
 *
 * <p>子がある集約は、宣言した子すべての {@code ChildRows} を渡す。渡さない子があると {@link TableWriter} は {@link
 * IllegalArgumentException} を投げる。渡さないことを「変更なし」と読むと、次の保存で消えるべき子が残るため、渡し忘れを許さない。
 *
 * <p>行の不備（自然キーが {@code null} か重複する、行ごとに書く列が違う）は、{@link ChildTable#rows} が SQL を実行する前に {@link
 * IllegalArgumentException} にする。
 *
 * @param <C> 子のテーブルの Record の型
 */
// 宣言を読むアクセサ（child()、rows()）はフィールドと同名にし、static factory の名前を of にする。
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName"})
public final class ChildRows<C extends Record> {

  /** 子の宣言。 */
  private final ChildTable<C> child;

  /** 子の全行。自然キーの値の組で引く。行の順を保つ。 */
  private final Map<List<Object>, ColumnValues<C>> rows;

  private ChildRows(final ChildTable<C> child, final Map<List<Object>, ColumnValues<C>> rows) {
    this.child = child;
    this.rows = rows;
  }

  /** 子の行を作り、自然キーの値の組で引く形にして検査する。 */
  /* package */ static <C extends Record, T> ChildRows<C> of(
      final ChildTable<C> child,
      final Collection<? extends T> items,
      final BiConsumer<? super T, ColumnValues<C>> columns) {
    // 一つの保存の呼び出しの間だけ、一つのスレッドで使う。
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    final Map<List<Object>, ColumnValues<C>> byKey = new LinkedHashMap<>();
    Set<Field<?>> setColumns = Set.of();
    for (final T item : items) {
      final ColumnValues<C> row = newRow(child, columns, item);
      // 複数行の INSERT は行ごとの列の組が同じでなければ書けないため、ここでそろえさせる。
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
    return new ChildRows<>(child, byKey);
  }

  private static <C extends Record, T> ColumnValues<C> newRow(
      final ChildTable<C> child,
      final BiConsumer<? super T, ColumnValues<C>> columns,
      final T item) {
    final ColumnValues<C> row = new ColumnValues<>(child.table(), List.of(child.parentKey()));
    columns.accept(item, row);
    return row;
  }

  /* package */ ChildTable<C> child() {
    return child;
  }

  /** 子の全行を、自然キーの値の組で引ける形で返す。 */
  /* package */ Map<List<Object>, ColumnValues<C>> rows() {
    return rows;
  }
}
