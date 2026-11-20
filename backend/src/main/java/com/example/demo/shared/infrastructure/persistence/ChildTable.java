package com.example.demo.shared.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;

/**
 * 集約の子のテーブルの宣言。Repository がクラスの定数として一度だけ書く（ADR-054）。
 *
 * <p>自然キーは、子のテーブルの一意制約で裏づける。裏づけがないと、保存済みの子を自然キーで引いた更新が 2 行に合い、{@link IllegalStateException} になる。
 *
 * <p>親の中で行を同定する自然キー（{@code line_no} など）と、親の内部の主キーを持つ外部キーの列を持つ。{@link TableWriter}
 * は、子の書き込みのたびに外部キーの列の条件を自分で付けるため、親の条件を書き忘れた子の書き込みは書けない。
 *
 * @param <R> 親（集約ルート）のテーブルの Record の型。{@link AggregateTable} が同じ型の子だけを受け取る
 * @param <C> 子のテーブルの Record の型
 */
// R は実行時に使わない。AggregateTable が同じ型 R の子だけを受け取るため、別の集約のために型を付けた定数を、そのまま使い回すとコンパイルで失敗する。
@SuppressWarnings({
  "UnusedTypeParameter",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortMethodName"
})
public final class ChildTable<R extends Record, C extends Record> {

  /** 自然キーに使える型。{@code BigDecimal} の桁数や {@code Instant} の精度の違いで、保存済みの値と一致しなくなる型は使えない。 */
  private static final Set<Class<?>> COMPARABLE_TYPES =
      Set.of(Integer.class, Long.class, Short.class, String.class, UUID.class);

  /** 子のテーブル。 */
  private final Table<C> table;

  /** 親の内部の主キーを持つ外部キーの列。 */
  private final TableField<C, Long> parentKey;

  /** 親の中で行を同定する列。一つ以上。 */
  private final List<TableField<C, ?>> naturalKey;

  private ChildTable(
      final Table<C> table,
      final TableField<C, Long> parentKey,
      final List<TableField<C, ?>> naturalKey) {
    this.table = table;
    this.parentKey = parentKey;
    this.naturalKey = List.copyOf(naturalKey);
  }

  /**
   * 子のテーブルを宣言する。
   *
   * @param table 子のテーブル
   * @param parentKey 親の内部の主キーを持つ外部キーの列
   * @param firstKey 親の中で行を同定する列
   * @param moreKeys 自然キーが複数の列のときの残りの列
   * @throws IllegalArgumentException テーブルに共通カラムがない場合
   */
  @SafeVarargs
  public static <R extends Record, C extends Record> ChildTable<R, C> of(
      final Table<C> table,
      final TableField<C, Long> parentKey,
      final TableField<C, ?> firstKey,
      final TableField<C, ?>... moreKeys) {
    CommonColumns.requireAuditColumns(table);
    final List<TableField<C, ?>> naturalKey = new ArrayList<>();
    naturalKey.add(firstKey);
    naturalKey.addAll(List.of(moreKeys));
    naturalKey.forEach(ChildTable::requireComparableType);
    return new ChildTable<>(table, parentKey, naturalKey);
  }

  /**
   * 子の行の自然キーの値の組を返す。
   *
   * @throws IllegalArgumentException 自然キーの列を書いていない、または式を書いた場合
   */
  /* package */ List<Object> naturalKeyOf(final ColumnValues<C> row) {
    return naturalKey.stream().map(field -> naturalKeyValue(row, field)).toList();
  }

  private Object naturalKeyValue(final ColumnValues<C> row, final TableField<C, ?> field) {
    final Object value = row.values().get(field);
    if (value == null || value instanceof Field<?>) {
      throw new IllegalArgumentException(
          "natural key must be a non-null value: table="
              + table.getName()
              + ", column="
              + field.getName());
    }
    return value;
  }

  /** 自然キーの値は、保存済みの値と {@code equals} で比べるため、比べられる型に限る。 */
  private static void requireComparableType(final TableField<?, ?> field) {
    if (!COMPARABLE_TYPES.contains(field.getType())) {
      throw new IllegalArgumentException(
          "natural key column must be Integer, Long, Short, String or UUID: column="
              + field.getName()
              + ", type="
              + field.getType().getSimpleName());
    }
  }

  /* package */ Table<C> table() {
    return table;
  }

  /* package */ TableField<C, Long> parentKey() {
    return parentKey;
  }

  /* package */ List<TableField<C, ?>> naturalKey() {
    return naturalKey;
  }
}
