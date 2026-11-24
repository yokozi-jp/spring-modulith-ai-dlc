package archfixture.conforming.ordering.domain.service;

import archfixture.conforming.shared.tenancy.TenantId;
import org.springframework.stereotype.Service;

/** 注文を扱えるテナントを判定する業務規則。Domain Service が shared の TenantId を使う例。 */
@Service
public class OrderTenantPolicy {

  /** 注文のテナントが、呼び出し中のテナントと同じかを返す。 */
  public boolean isOwnedBy(final TenantId orderTenant, final TenantId callerTenant) {
    return orderTenant.equals(callerTenant);
  }
}
