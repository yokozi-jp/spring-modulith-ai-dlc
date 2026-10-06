package archfixture.conforming.shared.infrastructure.persistence;

/** 業務テーブルの UPDATE と DELETE の唯一の入口。フィクスチャなので jOOQ を使わず、規則が見る名前だけを持つ。 */
public final class TableWriter {

  /** 期待する版の行を更新する。 */
  public String updateCheckingVersion(final String table, final long expectedLockNo) {
    return table + expectedLockNo;
  }

  /** 期待する版の行を削除する。 */
  public String deleteCheckingVersion(final String table, final long expectedLockNo) {
    return table + expectedLockNo;
  }

  /** 版を比べずに更新し、件数を返す。 */
  public int updateWhere(final String table) {
    return table.length();
  }

  /** 版を比べずに削除し、件数を返す。 */
  public int deleteWhere(final String table) {
    return table.length();
  }
}
