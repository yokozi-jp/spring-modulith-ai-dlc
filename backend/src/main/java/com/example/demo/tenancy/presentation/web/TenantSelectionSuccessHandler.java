package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.security.AppUserPrincipal;
import com.example.demo.tenancy.MembershipSummary;
import com.example.demo.tenancy.TenancyQueries;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

/**
 * ログインの後、所属が 1 つだけならそのテナントを選んでから、{@code /} へ戻す。
 *
 * <p>所属がないか 2 つ以上なら選ばない。SPA が、所属を待つ画面かテナントを選ぶ画面を出す。chain は要求を保存しない（{@code
 * NullRequestCache}）ので、バックエンドは常に {@code /} へ戻す。元の画面へは、SPA が保存した戻り先を {@code /} で読んで移す。
 */
final class TenantSelectionSuccessHandler implements AuthenticationSuccessHandler {

  /** 選ばずにそのテナントへ入る、所属の数。 */
  private static final int SOLE_MEMBERSHIP = 1;

  /** {@code /} へ戻す。 */
  private final AuthenticationSuccessHandler redirect =
      new SimpleUrlAuthenticationSuccessHandler("/");

  /** 利用者の所属を引く参照。 */
  private final TenancyQueries tenancyQueries;

  /** セッションで選んだテナント。 */
  private final TenantSession tenantSession;

  /** 依存を受け取る。 */
  /* package */ TenantSelectionSuccessHandler(
      final TenancyQueries tenancyQueries, final TenantSession tenantSession) {
    this.tenancyQueries = tenancyQueries;
    this.tenantSession = tenantSession;
  }

  @Override
  public void onAuthenticationSuccess(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Authentication authentication)
      throws IOException, ServletException {
    if (authentication.getPrincipal() instanceof AppUserPrincipal user) {
      final List<MembershipSummary> memberships = tenancyQueries.memberships(user.userId());
      if (memberships.size() == SOLE_MEMBERSHIP) {
        tenantSession.select(request, response, authentication, memberships.getFirst());
      }
    }
    redirect.onAuthenticationSuccess(request, response, authentication);
  }
}
