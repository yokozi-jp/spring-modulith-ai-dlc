package archfixture.violating.ordering.application;

import org.springframework.security.access.prepost.PreAuthorize;

/** 違反：preAuthorizeUsesOnlyRoleOrAuthorityNames（@PreAuthorize に役割か権限の名前だけでない式を書く）。 */
@PreAuthorize("isAuthenticated()")
public class ScriptedAuthorizationCommandHandler {

  /** 引数と利用者を比べる SpEL で許す。 */
  @PreAuthorize("#orderId == principal.userId")
  public void approve(final String orderId) {
    // 式だけを検査するため、何もしない。
  }

  /** Bean を呼ぶ SpEL で許す。 */
  @PreAuthorize("@orderPolicy.canReject(#orderId)")
  public void reject(final String orderId) {
    // 式だけを検査するため、何もしない。
  }

  /** or で組んだ式で許す。 */
  @PreAuthorize("hasRole('ADMIN') or hasRole('MEMBER')")
  public void hold() {
    // 式だけを検査するため、何もしない。
  }

  /** 引用符のない役割で許す。 */
  @PreAuthorize("hasRole(ADMIN)")
  public void ship() {
    // 式だけを検査するため、何もしない。
  }

  /** 空の式で許す。value は必須で省けないため、省いた注釈の代わりに空の値を置く。 */
  @PreAuthorize("")
  public void release() {
    // 式だけを検査するため、何もしない。
  }
}
