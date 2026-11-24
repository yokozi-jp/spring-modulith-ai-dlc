package com.example.demo.shared.persistence;

import com.example.demo.shared.concurrency.Versioned;
import java.util.List;
import java.util.function.Function;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;

/**
 * 集約の形の宣言。Repository がクラスの定数として一度だけ書く（ADR-054）。
 *
 * <p>集約ルートのテーブル、ルートを一つに決める一意の列（{@code public_id} など）と集約ルートからその値を取る関数、ルートの内部の主キー、子のテーブルを持つ。{@link
 * TableWriter} は、この宣言から版の条件と子の外部キーの条件を作るため、Repository は {@code Condition} を渡さない。
 *
 * <p>宣言は構造と、ルートを一つに決める値の取り方（{@code keyOf}）だけを持つ。業務の値から列への変換は、保存のたびの呼び出しのラムダが持つ。
 *
 * @param <R> 集約ルートのテーブルの Record の型
 * @param <K> ルートを一つに決める一意の列の値の型
 * @param <A> 集約ルートのクラス。{@link TableWriter#update} が受け取る版の持ち主で、このクラスを指定すると、版の数値を返すラムダを渡せない
 */
// 宣言を読むアクセサ（table()、key() など）はフィールドと同名にし、宣言の static factory の名前を of にする。
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName"})
public final class AggregateTable<R extends Record, K, A extends Versioned> {

  /** 集約ルートのテーブル。 */
  private final Table<R> table;

  /** ルートを一つに決める一意の列。 */
  private final TableField<R, K> key;

  /** 集約ルートから、ルートを一つに決める一意の列の値を取る。 */
  private final Function<A, K> keyOf;

  /** ルートの内部の主キー。子の外部キーが参照する。 */
  private final TableField<R, Long> id;

  /** 子のテーブル。 */
  private final List<ChildTable<?>> children;

  private AggregateTable(
      final Table<R> table,
      final TableField<R, K> key,
      final Function<A, K> keyOf,
      final TableField<R, Long> id,
      final List<ChildTable<?>> children) {
    this.table = table;
    this.key = key;
    this.keyOf = keyOf;
    this.id = id;
    this.children = List.copyOf(children);
  }

  /**
   * 集約を宣言する。
   *
   * @param table 集約ルートのテーブル
   * @param key ルートを一つに決める一意の列。{@code public_id} など
   * @param keyOf 集約ルートから {@code key} の値を取る。{@link TableWriter}
   *     は、渡された集約ルートの値でルートの行を引くため、別の集約ルートの値と組にして渡す誤りが書けない
   * @param id ルートの内部の主キー
   * @param children 子のテーブル。なければ省く
   * @throws IllegalArgumentException テーブルに共通カラムがない場合
   */
  public static <R extends Record, K, A extends Versioned> AggregateTable<R, K, A> of(
      final Table<R> table,
      final TableField<R, K> key,
      final Function<A, K> keyOf,
      final TableField<R, Long> id,
      final ChildTable<?>... children) {
    CommonColumns.requireAuditColumns(table);
    return new AggregateTable<>(table, key, keyOf, id, List.of(children));
  }

  /* package */ Table<R> table() {
    return table;
  }

  /* package */ TableField<R, K> key() {
    return key;
  }

  /* package */ K keyOf(final A root) {
    return keyOf.apply(root);
  }

  /* package */ TableField<R, Long> id() {
    return id;
  }

  /* package */ List<ChildTable<?>> children() {
    return children;
  }
}
