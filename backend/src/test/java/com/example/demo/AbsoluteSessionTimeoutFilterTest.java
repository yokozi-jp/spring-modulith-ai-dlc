package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link AbsoluteSessionTimeoutFilter} が、作成から最長の期間がたったセッションだけを無効にし、どの場合も後の filter へ進むことを、Spring
 * を起動せずに検証する。
 *
 * <p>実際の filter chain での 401 とセッションの行の削除は {@code TenantSelectionTest} が確かめる。
 */
class AbsoluteSessionTimeoutFilterTest {

  /** セッションを作ってから使える最長の期間。 */
  private static final Duration MAX_LIFETIME = Duration.ofHours(8);

  /** 現在時刻。セッションの作成の時刻はミリ秒なので、期限のちょうどを作れるようマイクロ秒の端数を 0 にする。 */
  private static final Instant NOW = Instant.parse("2026-11-26T09:00:00.123000Z");

  /** テストの対象。 */
  private final AbsoluteSessionTimeoutFilter filter =
      new AbsoluteSessionTimeoutFilter(Clock.fixed(NOW, ZoneOffset.UTC), MAX_LIFETIME);

  /** 後の filter。呼ばれると受け取ったリクエストを持つ。 */
  private final MockFilterChain chain = new MockFilterChain();

  @Test
  @DisplayName("作ってから最長の期間ちょうどのセッションを無効にし、後の filter へ進む")
  void invalidatesSessionAtMaxLifetime() throws ServletException, IOException {
    final HttpSession session = sessionCreatedAt(NOW.minus(MAX_LIFETIME));
    final MockHttpServletRequest request = requestWith(session);

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    Mockito.verify(session).invalidate();
    assertSame(request, chain.getRequest(), "無効にした後も後の filter へ進むこと");
  }

  @Test
  @DisplayName("最長の期間の 1 ミリ秒前のセッションは無効にせず、後の filter へ進む")
  void keepsSessionBeforeMaxLifetime() throws ServletException, IOException {
    final HttpSession session = sessionCreatedAt(NOW.minus(MAX_LIFETIME).plusMillis(1));
    final MockHttpServletRequest request = requestWith(session);

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    Mockito.verify(session, Mockito.never()).invalidate();
    assertSame(request, chain.getRequest(), "後の filter へ進むこと");
  }

  @Test
  @DisplayName("セッションのないリクエストは、セッションを作らずに後の filter へ進む")
  void passesRequestWithoutSession() throws ServletException, IOException {
    final MockHttpServletRequest request = new MockHttpServletRequest();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertSame(request, chain.getRequest(), "後の filter へ進むこと");
    assertNull(request.getSession(false), "filter がセッションを作らないこと");
  }

  /** {@code session} を持つリクエスト。 */
  private static MockHttpServletRequest requestWith(final HttpSession session) {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setSession(session);
    return request;
  }

  /** {@code createdAt} に作ったセッション。 */
  private static HttpSession sessionCreatedAt(final Instant createdAt) {
    final HttpSession session = Mockito.mock(HttpSession.class);
    Mockito.when(session.getCreationTime()).thenReturn(createdAt.toEpochMilli());
    return session;
  }
}
