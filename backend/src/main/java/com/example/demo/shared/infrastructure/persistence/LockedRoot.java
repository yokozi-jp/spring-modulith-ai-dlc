package com.example.demo.shared.infrastructure.persistence;

import java.util.function.Consumer;
import org.jooq.Condition;
import org.jooq.Record;
import org.jooq.Table;

/**
 * 集約ルートの行を、このトランザクションで版を比べて更新した印（ADR-054）。{@link TableWriter#updateCheckingVersion} だけが作る。
 *
 * <p>ルートの行ロックを持つため、ほかのトランザクションは {@link TableWriter} の経路で同じ集約の子を変えられない。子の行の追加は INSERT
 * なのでここを通さず、{@link CommonColumns#forInsert} で書く。
 */
public final class LockedRoot {

  /** 子の主キーの条件で更新できる行数。 */
  private static final int ONE_ROW = 1;

  /** 子の行の UPDATE と DELETE を実行する。 */
  private final TableWriter writer;

  /* package */ LockedRoot(final TableWriter writer) {
    this.writer = writer;
  }

  /**
   * 子の行を主キーで 1 行更新し、その行の版を 1 進める。
   *
   * @param table 子のテーブル
   * @param byPrimaryKey 子の主キーで 1 行を特定する条件
   * @param businessColumns 業務の列の値を {@link ColumnValues} に登録する。一つ以上必要
   * @throws IllegalArgumentException テーブルに {@code lock_no} がない場合、業務の列が一つもない場合、共通カラムを渡した場合
   * @throws IllegalStateException 更新件数が 1 でない場合。子の行の欠落か、主キーの条件の誤り
   */
  public <R extends Record> void updateChild(
      final Table<R> table,
      final Condition byPrimaryKey,
      final Consumer<ColumnValues<R>> businessColumns) {
    // ponytail: 子の行ごとに往復する。子の行が多い集約が出たら、LockedRoot の中で dsl.batch にし、件数の配列で各 1 件を確かめる。
    final int updated = writer.updateWhere(table, byPrimaryKey, businessColumns);
    if (updated != ONE_ROW) {
      throw new IllegalStateException(
          "child primary key condition matched "
              + updated
              + " rows: table="
              + table.getName()
              + ", key="
              + writer.bindValuesOf(byPrimaryKey));
    }
  }

  /**
   * 子の行を条件で削除する。子の集合が減ったときの差分の削除に使う。
   *
   * @param table 子のテーブル
   * @param where 削除する子の行の条件
   */
  public void deleteChildren(final Table<?> table, final Condition where) {
    writer.deleteRows(table, where);
  }
}
