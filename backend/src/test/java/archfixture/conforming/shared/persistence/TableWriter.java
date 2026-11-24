package archfixture.conforming.shared.persistence;

import archfixture.conforming.shared.concurrency.Versioned;

/** 業務テーブルの INSERT と UPDATE の唯一の入口。フィクスチャなので jOOQ を使わず、規則が見る名前だけを持つ。 */
public final class TableWriter {

  /** 集約を INSERT する。 */
  public String insert(final String table) {
    return table;
  }

  /** 以前に読んだ集約ルートの版の行を更新する。 */
  public String update(final String table, final Versioned root) {
    return table + root.lockNo();
  }
}
