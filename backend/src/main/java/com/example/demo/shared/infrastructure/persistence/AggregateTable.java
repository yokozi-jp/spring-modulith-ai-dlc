package com.example.demo.shared.infrastructure.persistence;

import java.util.List;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;

/**
 * 集約の形の宣言。Repository がクラスの定数として一度だけ書く（ADR-054）。
 *
 * <p>集約ルートのテーブル、ルートを一つに決める一意の列（{@code public_id} など）、ルートの内部の主キー、子のテーブルを持つ。{@link TableWriter}
 * は、この宣言から版の条件と子の外部キーの条件を作るため、Repository は {@code Condition} を渡さない。
 *
 * <p>宣言は構造だけを持つ。業務の値から列への変換は、保存のたびの呼び出しのラムダが持つ。
 *
 * @param <R> 集約ルートのテーブルの Record の型
 * @param <K> ルートを一つに決める一意の列の値の型
 */
// 宣言を読むアクセサ（table()、key() など）はフィールドと同名にし、宣言の static factory の名前を of にする。
@SuppressWarnings({"PMD.DataClass", "PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName"})
public final class AggregateTable<R extends Record, K> {

  /** 集約ルートのテーブル。 */
  private final Table<R> table;

  /** ルートを一つに決める一意の列。 */
  private final TableField<R, K> key;

  /** ルートの内部の主キー。子の外部キーが参照する。 */
  private final TableField<R, Long> id;

  /** 子のテーブル。 */
  private final List<ChildTable<R, ?>> children;

  private AggregateTable(
      final Table<R> table,
      final TableField<R, K> key,
      final TableField<R, Long> id,
      final List<ChildTable<R, ?>> children) {
    this.table = table;
    this.key = key;
    this.id = id;
    this.children = List.copyOf(children);
  }

  /**
   * 集約を宣言する。
   *
   * @param table 集約ルートのテーブル
   * @param key ルートを一つに決める一意の列。{@code public_id} など
   * @param id ルートの内部の主キー
   * @param children 子のテーブル。なければ省く
   * @throws IllegalArgumentException テーブルに共通カラムがない場合
   */
  @SafeVarargs
  public static <R extends Record, K> AggregateTable<R, K> of(
      final Table<R> table,
      final TableField<R, K> key,
      final TableField<R, Long> id,
      final ChildTable<R, ?>... children) {
    CommonColumns.requireAuditColumns(table);
    return new AggregateTable<>(table, key, id, List.of(children));
  }

  /* package */ Table<R> table() {
    return table;
  }

  /* package */ TableField<R, K> key() {
    return key;
  }

  /* package */ TableField<R, Long> id() {
    return id;
  }

  /* package */ List<ChildTable<R, ?>> children() {
    return children;
  }
}
