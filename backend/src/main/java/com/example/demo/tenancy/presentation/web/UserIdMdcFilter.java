package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.security.AppUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@link AppUserPrincipal} として認証したリクエストの残りの処理の間だけ、アプリの利用者の ID を MDC の {@value
 * #USER_ID_ATTRIBUTE_KEY} に入れる。
 *
 * <p>認可の拒否、CSRF の検証の失敗、テナントを選ぶ前の拒否のログにも利用者の ID を付けるため、{@code TenantSecurityCustomizer} は、この filter
 * をセッションの認証を読む filter の直後、CSRF の検証の前に置く。
 */
final class UserIdMdcFilter extends OncePerRequestFilter {

  /**
   * ログとトレースで、アプリの利用者の ID（{@link
   * AppUserPrincipal#userId()}）を持つ項目の名前（docs/observability/conventions.md）。
   */
  /* package */ static final String USER_ID_ATTRIBUTE_KEY = "user.id";

  /** 匿名の認証を見分ける。 */
  private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

  /** このリクエストの認証を持つ。 */
  private final SecurityContextHolderStrategy holder =
      SecurityContextHolder.getContextHolderStrategy();

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    final @Nullable Authentication authentication = holder.getContext().getAuthentication();
    if (authentication == null
        || !trustResolver.isAuthenticated(authentication)
        || !(authentication.getPrincipal() instanceof AppUserPrincipal user)) {
      filterChain.doFilter(request, response);
      return;
    }
    try (MDC.MDCCloseable ignored = MDC.putCloseable(USER_ID_ATTRIBUTE_KEY, user.userId())) {
      filterChain.doFilter(request, response);
    }
  }
}
