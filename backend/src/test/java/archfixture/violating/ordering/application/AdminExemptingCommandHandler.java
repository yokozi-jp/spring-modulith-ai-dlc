package archfixture.violating.ordering.application;

import com.example.demo.shared.security.NoRoleRequired;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 違反：authorizationIsDeclaredOnlyOnce（@PreAuthorize を付けたクラスのメソッドに @NoRoleRequired を付ける）。
 *
 * <p>Spring はクラスの {@code @PreAuthorize} をこのメソッドにも当てるため、役割を問わないという宣言は効かない。
 */
@PreAuthorize("hasRole('ADMIN')")
public class AdminExemptingCommandHandler {

  /** クラスの役割の確かめを、メソッドで外したつもりになる。 */
  @NoRoleRequired(NoRoleRequired.Basis.TENANT_SCOPED)
  public void handle() {
    // 宣言だけを検査するため、何もしない。
  }
}
