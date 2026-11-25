package archfixture.violating.ordering.domain.model;

import archfixture.violating.shared.tenancy.TenantContext;

/** 違反：domainModelDependsOnlyOnAllowedTypes（domain.model が shared.tenancy.TenantContext を読む）。 */
public final class TenantReadingOrder {

  /** 呼び出し中のテナントを読む。 */
  public String tenant() {
    return TenantContext.current();
  }
}
