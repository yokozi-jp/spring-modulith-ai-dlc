package com.example.demo;

import com.example.demo.shared.security.CrossOriginRequestRejectedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** 別のオリジンから来た更新系の要求を、CSRF のトークンを確かめる前に拒否する（ADR-136）。 */
final class CrossOriginRequestFilter extends OncePerRequestFilter {

  /** CSRF のトークンを求めない method。frontend の apiFetch と同じ集合にする。 */
  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

  /** 通す {@code Sec-Fetch-Site} の値。{@code none} は利用者が直接開いた要求である。 */
  private static final Set<String> ALLOWED_FETCH_SITES = Set.of("same-origin", "none");

  /** 検査する path。{@code /api/**} と {@code /logout} である。 */
  private final RequestMatcher targets;

  /** 拒否を {@code /api/**} の CSRF の失敗と同じ Problem Details の変換へ渡す。 */
  private final AccessDeniedHandler accessDeniedHandler;

  /* package */ CrossOriginRequestFilter(
      final RequestMatcher apiRequests, final AccessDeniedHandler accessDeniedHandler) {
    super();
    this.targets =
        new OrRequestMatcher(
            apiRequests, PathPatternRequestMatcher.withDefaults().matcher("/logout"));
    this.accessDeniedHandler = accessDeniedHandler;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    if (SAFE_METHODS.contains(request.getMethod())
        || !targets.matches(request)
        || !isCrossOrigin(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    accessDeniedHandler.handle(
        request,
        response,
        new CrossOriginRequestRejectedException("Cross-origin request rejected"));
  }

  private static boolean isCrossOrigin(final HttpServletRequest request) {
    final @Nullable String fetchSite = request.getHeader("Sec-Fetch-Site");
    if (fetchSite != null && !ALLOWED_FETCH_SITES.contains(fetchSite)) {
      return true;
    }
    try {
      return CorsUtils.isCorsRequest(request);
    } catch (IllegalArgumentException | IllegalStateException malformed) {
      // 解釈できない Origin（InvalidUrlException、範囲外の port）は、同じオリジンだと示せないので拒否する。
      return true;
    }
  }
}
