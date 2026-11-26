package com.example.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 作ってから最長の期間を過ぎたセッションを、操作の有無によらず無効にする。
 *
 * <p>Spring Session の期限は最後の操作から測る無操作の期間だけなので、操作を続けるとセッションは切れない。 期間はセッションの作成の時刻から測る。 セッションの ID
 * を作り直しても、作成の時刻は変わらない。
 *
 * <p>401 は自分で返さない。無効にした後のリクエストは未認証として進み、API の認証の entry point が 401 にする。 拒否だけで行を残すと、
 * 再ログインが古い作成の時刻を引き継いで 401 を繰り返すため、無効にして行を消す。
 */
final class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

  /** 現在時刻を取る Clock。 */
  private final Clock clock;

  /** セッションを作ってから使える最長の期間。 */
  private final Duration maxLifetime;

  /** 依存と最長の期間を受け取る。 */
  /* package */ AbsoluteSessionTimeoutFilter(final Clock clock, final Duration maxLifetime) {
    super();
    this.clock = clock;
    this.maxLifetime = maxLifetime;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    final @Nullable HttpSession session = request.getSession(false);
    if (session != null && isExpired(session)) {
      session.invalidate();
    }
    filterChain.doFilter(request, response);
  }

  /** 作成の時刻から最長の期間がたっていれば真。期限のちょうどの時刻は過ぎたとみなす。 */
  private boolean isExpired(final HttpSession session) {
    final Instant created = Instant.ofEpochMilli(session.getCreationTime());
    return Duration.between(created, Instant.now(clock)).compareTo(maxLifetime) >= 0;
  }
}
