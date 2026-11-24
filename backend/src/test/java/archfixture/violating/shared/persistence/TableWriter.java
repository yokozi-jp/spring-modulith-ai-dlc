package archfixture.violating.shared.persistence;

/** 業務テーブルの INSERT と UPDATE の唯一の入口。フィクスチャなので jOOQ を使わず、規則が見る名前だけを持つ。 */
public final class TableWriter {

  /** 集約ルートの版を比べて更新する。 */
  public String update(final String table) {
    return table;
  }

  /** 集約ルートの版を比べずに書く。 */
  public String execute(final String table) {
    return table;
  }
}
