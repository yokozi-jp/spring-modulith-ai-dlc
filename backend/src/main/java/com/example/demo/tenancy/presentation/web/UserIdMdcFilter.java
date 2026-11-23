package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.security.AppUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@link AppUserPrincipal} として認証したリクエストの残りの処理の間だけ、アプリの利用者の ID を MDC の {@value
 * #USER_ID_ATTRIBUTE_KEY} に入れる。
 *
 * <p>配置の理由は {@link TenantSecurityCustomizer} に書く。
 */
final class UserIdMdcFilter extends OncePerRequestFilter {

  /**
   * ログとトレースで、アプリの利用者の ID（{@link
   * AppUserPrincipal#userId()}）を持つ項目の名前（docs/observability/conventions.md）。
   */
  /* package */ static final String USER_ID_ATTRIBUTE_KEY = "user.id";

  /** 認証を読む。この filter は匿名の認証を足す filter より前にあるので、匿名の認証は見分けない。 */
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
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof AppUserPrincipal user)) {
      filterChain.doFilter(request, response);
      return;
    }
    try (MDC.MDCCloseable ignored = MDC.putCloseable(USER_ID_ATTRIBUTE_KEY, user.userId())) {
      filterChain.doFilter(request, response);
    }
  }
}
