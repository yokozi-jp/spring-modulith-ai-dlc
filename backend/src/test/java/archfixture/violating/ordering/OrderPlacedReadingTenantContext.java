package archfixture.violating.ordering;

import archfixture.violating.shared.tenancy.TenantContext;

/**
 * 違反：moduleApiDoesNotExposeInternalTypes（ルートの record が shared.tenancy の TenantId と TenantScoped
 * 以外の型を使う）。
 */
public record OrderPlacedReadingTenantContext(String orderId) {

  /** 呼び出し中のテナントを読む。 */
  public String tenantId() {
    return TenantContext.current();
  }
}
