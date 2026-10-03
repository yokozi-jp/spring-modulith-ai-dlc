package archfixture.violating.shared.infrastructure.persistence;

import java.util.Map;

/** INSERT で共通カラムに入れる値を作る shared の共通処理。フィクスチャなので値を持たない。 */
public final class CommonColumns {

  /** INSERT で共通カラムに入れる値を返す。 */
  public Map<String, Object> forInsert() {
    return Map.of();
  }
}
