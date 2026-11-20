package com.example.demo.shared.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;

/**
 * 集約の子のテーブルの宣言。Repository がクラスの定数として一度だけ書く（ADR-054）。
 *
 * <p>親の中で行を同定する自然キー（{@code line_no} など）と、親の内部の主キーを持つ外部キーの列を持つ。{@link TableWriter}
 * は、子の書き込みのたびに外部キーの列の条件を自分で付けるため、親の条件を書き忘れた子の書き込みは書けない。
 *
 * @param <R> 親（集約ルート）のテーブルの Record の型。{@link AggregateTable} が同じ型の子だけを受け取る
 * @param <C> 子のテーブルの Record の型
 */
// R は実行時に使わない。AggregateTable が同じ型 R の子だけを受け取るため、別の集約の子を渡すとコンパイルで失敗する。
@SuppressWarnings({
  "UnusedTypeParameter",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortMethodName"
})
public final class ChildTable<R extends Record, C extends Record> {

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
    return new ChildTable<>(table, parentKey, naturalKey);
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
