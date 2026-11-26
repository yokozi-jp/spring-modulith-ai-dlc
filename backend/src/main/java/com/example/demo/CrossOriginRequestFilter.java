package com.example.demo;

import com.example.demo.shared.security.CrossOriginRequestRejectedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.InvalidUrlException;

/**
 * 別のオリジンから来た {@code /api/**} と {@code /logout} の更新系の要求を、CSRF の token を確かめる前に 403 にする（ADR-134）。
 *
 * <p>{@code Origin} が別のオリジン（{@code Origin: null} を含む）か、{@code Sec-Fetch-Site} が {@code same-origin}
 * と {@code none} 以外なら拒否する。どちらの header もない要求は通し、CSRF の token の検査に任せる。オリジンが同じかは {@link
 * CorsUtils#isCorsRequest} が、X-Forwarded-* を反映した scheme、host、port と比べて決める。
 */
final class CrossOriginRequestFilter extends OncePerRequestFilter {

  /** CSRF の token を求めない method。frontend の apiFetch と同じ集合にする。 */
  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

  /** 通す {@code Sec-Fetch-Site} の値。{@code none} は利用者が直接開いた要求である。 */
  private static final Set<String> ALLOWED_FETCH_SITES = Set.of("same-origin", "none");

  /** 検査する path。CSRF の token を求める path のうち、ブラウザのセッションで使うものである。 */
  private static final RequestMatcher TARGETS =
      new OrRequestMatcher(
          PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
          PathPatternRequestMatcher.withDefaults().matcher("/logout"));

  /** 拒否を {@code /api/**} の CSRF の失敗と同じ Problem Details の変換へ渡す。 */
  private final HandlerExceptionResolver handlerExceptionResolver;

  /** 拒否を渡す resolver を受け取る。 */
  /* package */ CrossOriginRequestFilter(final HandlerExceptionResolver handlerExceptionResolver) {
    super();
    this.handlerExceptionResolver = handlerExceptionResolver;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    if (SAFE_METHODS.contains(request.getMethod())
        || !TARGETS.matches(request)
        || !isCrossOrigin(request)) {
      filterChain.doFilter(request, response);
      return;
    }
    SecurityConfig.resolveOrSendError(
        handlerExceptionResolver,
        request,
        response,
        new CrossOriginRequestRejectedException("Cross-origin request rejected"),
        HttpStatus.FORBIDDEN);
  }

  /** {@code Origin} か {@code Sec-Fetch-Site} が別のオリジンを示せば true を返す。どちらもなければ false にする。 */
  private static boolean isCrossOrigin(final HttpServletRequest request) {
    final @Nullable String fetchSite = request.getHeader("Sec-Fetch-Site");
    if (fetchSite != null && !ALLOWED_FETCH_SITES.contains(fetchSite)) {
      return true;
    }
    try {
      return CorsUtils.isCorsRequest(request);
    } catch (InvalidUrlException malformed) {
      // 解釈できない Origin は、同じオリジンだと示せないので拒否する。
      return true;
    }
  }
}
