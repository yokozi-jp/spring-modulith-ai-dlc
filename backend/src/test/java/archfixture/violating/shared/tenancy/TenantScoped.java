package archfixture.violating.shared.tenancy;

/** 違反フィクスチャが使う、テナントを運ぶイベントの型。これ自体は規約どおり。 */
@FunctionalInterface
public interface TenantScoped {

  /** イベントが発生したテナント。 */
  String tenantId();
}
