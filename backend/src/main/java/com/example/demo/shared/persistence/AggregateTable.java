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
 * <p>同じ集約の 2 回目の保存を処理済みとして正常に終える集約は、重複を許す一意索引の列を {@link #ofIdempotentOn} で宣言する。{@link
 * TableWriter#insert} は、この列の衝突を例外にせず、入れなかったことを返す。
 *
 * <p>宣言は構造（重複を許す一意索引を含む）と、ルートを一つに決める値の取り方（{@code keyOf}）だけを持つ。業務の値から列への変換は、保存のたびの呼び出しのラムダが持つ。
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

  /** 重複を許す一意索引の列。空なら重複を許さない。 */
  private final List<TableField<R, ?>> duplicateKey;

  private AggregateTable(
      final Table<R> table,
      final TableField<R, K> key,
      final Function<A, K> keyOf,
      final TableField<R, Long> id,
      final List<ChildTable<?>> children,
      final List<? extends TableField<R, ?>> duplicateKey) {
    this.table = table;
    this.key = key;
    this.keyOf = keyOf;
    this.id = id;
    this.children = List.copyOf(children);
    this.duplicateKey = List.copyOf(duplicateKey);
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
    return new AggregateTable<>(table, key, keyOf, id, List.of(children), List.of());
  }

  /**
   * 同じ集約の 2 回目の保存を処理済みとする集約を宣言する。
   *
   * <p>{@link TableWriter#insert} は {@code INSERT ... ON CONFLICT (duplicateKey の列) DO NOTHING}
   * で書き、この列の衝突では子を書かずに {@code false} を返す。ほかの一意制約の違反は、{@link #of} の集約と同じく {@code ConflictException}
   * になる。
   *
   * @param table 集約ルートのテーブル
   * @param key ルートを一つに決める一意の列
   * @param keyOf 集約ルートから {@code key} の値を取る
   * @param id ルートの内部の主キー
   * @param duplicateKey 重複を許す一意索引の列。述語と式のない一意索引の列とちょうど同じ組にし（テナントの列を含む）、{@code key} を含めない
   * @param children 子のテーブル。なければ省く
   * @throws IllegalArgumentException テーブルに共通カラムがない場合、{@code duplicateKey} が空の場合、集約ルートのテーブルの列でない場合
   */
  public static <R extends Record, K, A extends Versioned> AggregateTable<R, K, A> ofIdempotentOn(
      final Table<R> table,
      final TableField<R, K> key,
      final Function<A, K> keyOf,
      final TableField<R, Long> id,
      final List<? extends TableField<R, ?>> duplicateKey,
      final ChildTable<?>... children) {
    CommonColumns.requireAuditColumns(table);
    if (duplicateKey.isEmpty()) {
      throw new IllegalArgumentException(
          "duplicate key must have one or more columns: table=" + table.getName());
    }
    for (final TableField<R, ?> column : duplicateKey) {
      if (!table.equals(column.getTable())) {
        throw new IllegalArgumentException(
            "duplicate key column must belong to the aggregate root table: table="
                + table.getName()
                + ", column="
                + column.getQualifiedName());
      }
    }
    return new AggregateTable<>(table, key, keyOf, id, List.of(children), duplicateKey);
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

  /* package */ List<TableField<R, ?>> duplicateKey() {
    return duplicateKey;
  }
}
