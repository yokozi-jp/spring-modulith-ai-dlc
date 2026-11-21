package archfixture.violating.ordering.application;

import archfixture.violating.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;

/** 違反：unboundTenantIsReadOnlyByTransactionManager（CommandHandler が束縛のないテナントを既定値で通す）。 */
@Service
public class TenantDefaultingCommandHandler {

  /** 束縛がなければ既定のテナントの注文 ID を返す。 */
  public String handle(final String orderId) {
    return TenantContext.findCurrent().orElse("default-tenant") + ":" + orderId;
  }
}
