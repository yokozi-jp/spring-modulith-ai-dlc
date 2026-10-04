package com.example.demo.shared.infrastructure.persistence;

import org.jooq.Condition;
import org.jooq.Table;

/**
 * 集約ルートの行を、このトランザクションで版を比べて削除した印（ADR-054）。{@link TableWriter#deleteCheckingVersion} だけが作る。
 *
 * <p>子の行の削除だけを持ち、削除したルートの子を更新するコードを型で書けなくする。
 */
public final class DeletedRoot {

  /** 子の行の DELETE を実行する。 */
  private final TableWriter writer;

  /* package */ DeletedRoot(final TableWriter writer) {
    this.writer = writer;
  }

  /**
   * 子の行を条件で削除する。
   *
   * @param table 子のテーブル
   * @param where 削除する子の行の条件（親の主キーの条件など）
   */
  public void deleteChildren(final Table<?> table, final Condition where) {
    writer.deleteRows(table, where);
  }
}
