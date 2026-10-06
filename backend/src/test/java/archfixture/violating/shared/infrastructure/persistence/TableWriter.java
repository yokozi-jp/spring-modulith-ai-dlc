package archfixture.violating.shared.infrastructure.persistence;

/** 業務テーブルの UPDATE と DELETE の唯一の入口。フィクスチャなので jOOQ を使わず、規則が見る名前だけを持つ。 */
public final class TableWriter {

  /** 期待する版の行を更新する。 */
  public String updateCheckingVersion(final String table, final long expectedLockNo) {
    return table + expectedLockNo;
  }

  /** 版を比べずに更新し、件数を返す。 */
  public int updateWhere(final String table) {
    return table.length();
  }
}
