package archfixture.violating.ordering.application;

import com.example.demo.shared.security.NoRoleRequired;
import org.springframework.security.access.prepost.PreAuthorize;

/** 違反：authorizationIsDeclaredOnlyOnce（同じメソッドに @PreAuthorize と @NoRoleRequired を付ける）。 */
public class DoublyAuthorizedCommandHandler {

  /** 役割を確かめると、確かめないとを両方宣言する。 */
  @PreAuthorize("hasRole('ADMIN')")
  @NoRoleRequired(NoRoleRequired.Basis.TENANT_SCOPED)
  public void handle() {
    // 宣言だけを検査するため、何もしない。
  }
}
