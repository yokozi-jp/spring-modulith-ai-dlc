package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.security.AppUserPrincipal;
import com.example.demo.shared.security.TenantNotSelectedException;
import com.example.demo.tenancy.MembershipSummary;
import com.example.demo.tenancy.TenancyQueries;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * セッションで選んだテナントの所属をリクエストのたびに引き直し、業務の API をテナントを選んだ後だけ通す。
 *
 * <p>所属が消えていれば、テナントの選択を外す。所属の役割が変わっていれば、所属の権限を作り直す。テナントを選ぶ前の利用者が業務の API を呼ぶと、{@link
 * TenantNotSelectedException} を投げる。所属の一覧とテナントを選ぶ API だけは、テナントを選ぶ前にも通す。
 */
final class TenantSelectionFilter extends OncePerRequestFilter {

  /** テナントが決まっている必要がある API。 */
  private static final RequestMatcher REQUIRES_TENANT;

  static {
    final PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
    REQUIRES_TENANT =
        new AndRequestMatcher(
            paths.matcher("/api/**"),
            new NegatedRequestMatcher(
                new OrRequestMatcher(
                    paths.matcher(HttpMethod.GET, MembershipController.PATH),
                    paths.matcher(HttpMethod.PUT, CurrentTenantController.PATH))));
  }

  /** 匿名の認証を見分ける。 */
  private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

  /** このリクエストの認証を持つ。 */
  private final SecurityContextHolderStrategy holder =
      SecurityContextHolder.getContextHolderStrategy();

  /** 所属を引き直す参照。 */
  private final TenancyQueries tenancyQueries;

  /** セッションで選んだテナント。 */
  private final TenantSession tenantSession;

  /** 依存を受け取る。 */
  /* package */ TenantSelectionFilter(
      final TenancyQueries tenancyQueries, final TenantSession tenantSession) {
    super();
    this.tenancyQueries = tenancyQueries;
    this.tenantSession = tenantSession;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    final @Nullable Authentication authentication = holder.getContext().getAuthentication();
    if (authentication != null && trustResolver.isAuthenticated(authentication)) {
      final Optional<TenantMembershipAuthority> tenant =
          TenantSession.selectedTenant(authentication);
      final boolean tenantSelected =
          tenant.isPresent() && recheck(request, response, authentication, tenant.get());
      if (REQUIRES_TENANT.matches(request) && !tenantSelected) {
        throw new TenantNotSelectedException("tenant not selected");
      }
    }
    filterChain.doFilter(request, response);
  }

  /** 選んだテナントの所属を引き直し、消えていれば選択を外し、役割が変わっていれば権限を作り直す。所属が残っていれば true を返す。 */
  private boolean recheck(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Authentication authentication,
      final TenantMembershipAuthority tenant) {
    final Optional<MembershipSummary> membership =
        authentication.getPrincipal() instanceof AppUserPrincipal user
            ? tenancyQueries.membership(user.userId(), tenant.tenantId())
            : Optional.empty();
    if (membership.isEmpty()) {
      tenantSession.clear(request, response, authentication);
      return false;
    }
    if (!membership.get().role().equals(tenant.role())) {
      tenantSession.update(request, response, authentication, membership.get());
    }
    return true;
  }
}
