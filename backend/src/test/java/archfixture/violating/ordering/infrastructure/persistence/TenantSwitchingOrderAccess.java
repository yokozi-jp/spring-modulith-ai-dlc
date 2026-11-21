package archfixture.violating.ordering.infrastructure.persistence;

import archfixture.violating.shared.infrastructure.persistence.ModuleRoleTransactionManager;

/** 違反：roleAndTenantAreSetOnlyByTransactionManager（永続化のアダプターが自分でロールとテナントを切り替える）。 */
public final class TenantSwitchingOrderAccess {

  private TenantSwitchingOrderAccess() {}

  /** 別のテナントの注文を読むために、ロールとテナントを切り替える。 */
  public static void readAs(final String tenant) {
    ModuleRoleTransactionManager.setLocal("ordering_module", tenant);
  }
}
