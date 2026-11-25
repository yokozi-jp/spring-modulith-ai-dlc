package archfixture.violating.ordering.domain.model;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 違反：methodSecurityAnnotationsAreOnlyOnApplicationClasses（domain の interface のメソッドに @PreAuthorize
 * を付ける）。
 */
@FunctionalInterface
public interface OrderApprovalPolicy {

  /** 管理者だけに、注文の承認を許す。 */
  @PreAuthorize("hasRole('ADMIN')")
  boolean approve(OrderId orderId);
}
