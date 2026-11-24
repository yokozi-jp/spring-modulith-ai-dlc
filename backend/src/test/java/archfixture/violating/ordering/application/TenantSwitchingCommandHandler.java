package archfixture.violating.ordering.application;

import archfixture.violating.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;

/** 違反：tenantIsBoundOnlyByRequestFiltersAndListenerAspect（CommandHandler が別のテナントへ束縛を切り替える）。 */
@Service
public class TenantSwitchingCommandHandler {

  /** 別のテナントを束縛して、注文 ID をそのまま返す。 */
  public String handle(final String orderId) {
    return TenantContext.call("other-tenant", () -> orderId);
  }
}
