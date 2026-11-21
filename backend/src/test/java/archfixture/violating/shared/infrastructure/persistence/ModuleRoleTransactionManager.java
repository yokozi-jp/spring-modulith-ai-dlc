package archfixture.violating.shared.infrastructure.persistence;

/** 違反フィクスチャが呼ぶ、ロールとテナントを設定する入口。これ自体は規約どおり。 */
public final class ModuleRoleTransactionManager {

  private ModuleRoleTransactionManager() {}

  /** ロールとテナントを設定する。フィクスチャは何もしない。 */
  public static void setLocal(final String role, final String tenant) {
    // 規則は呼び出しだけを見る。
  }
}
