package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.security.CrossOriginRequestRejectedException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * {@link CrossOriginRequestFilter} が拒否する要求と通す要求を、Spring を起動せずに検証する。
 *
 * <p>本番と同じく {@link ForwardedHeaderFilter} の後ろで動かし、X-Forwarded-* を反映したオリジンと比べることを確かめる。 拒否の応答と {@code
 * CsrfFilter} の前で動くことは {@code CrossOriginRequestContractTest} が確かめる。
 */
// 拒否と通過の組み合わせを 1 件ずつのテストで並べるため、メソッドの数の上限を外す。
@SuppressWarnings("PMD.TooManyMethods")
class CrossOriginRequestFilterTest {

  /** {@code /api/**} の中の、更新系の要求を受ける path。 */
  private static final String ORDERS = "/api/orders";

  /** {@code Sec-Fetch-Site} の header の名前。 */
  private static final String FETCH_SITE = "Sec-Fetch-Site";

  /** 別のオリジン。 */
  private static final String EVIL_ORIGIN = "https://evil.localhost";

  /** 公開のホスト。 */
  private static final String PUBLIC_HOST = "app.example.com";

  /** filter が拒否を渡した例外。 */
  private final List<AccessDeniedException> denied = new ArrayList<>();

  /** 検査する filter。拒否を {@link #denied} に記録する。 */
  private final CrossOriginRequestFilter filter =
      new CrossOriginRequestFilter(
          PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
          (request, response, exception) -> denied.add(exception));

  /** filter の後ろの処理。呼ばれると要求を記録する。 */
  private final MockFilterChain chain = new MockFilterChain();

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource({
    "Origin, https://evil.localhost",
    "Origin, http://localhost:8080",
    "Origin, null",
    "Origin, http://localhost:abc",
    "Sec-Fetch-Site, cross-site",
    "Sec-Fetch-Site, same-site",
    "Sec-Fetch-Site, ''",
  })
  @DisplayName("別のオリジンを示すか、解釈できない header の更新は拒否する")
  void rejectsHeaderThatIsNotSameOrigin(final String name, final String value)
      throws ServletException, IOException {
    final MockHttpServletRequest request = update(ORDERS);
    request.addHeader(name, value);

    doFilter(request);

    assertRejected();
  }

  @Test
  @DisplayName("同じオリジンの Origin でも、Sec-Fetch-Site が same-site なら拒否する")
  void rejectsSameSiteFetchEvenWithSameOrigin() throws ServletException, IOException {
    final MockHttpServletRequest request = update(ORDERS);
    request.addHeader(HttpHeaders.ORIGIN, "http://localhost");
    request.addHeader(FETCH_SITE, "same-site");

    doFilter(request);

    assertRejected();
  }

  @Test
  @DisplayName("/logout も検査する")
  void rejectsCrossOriginLogout() throws ServletException, IOException {
    final MockHttpServletRequest request = update("/logout");
    request.addHeader(HttpHeaders.ORIGIN, EVIL_ORIGIN);

    doFilter(request);

    assertRejected();
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource({
    "Origin, http://localhost",
    "Sec-Fetch-Site, same-origin",
    "Sec-Fetch-Site, none",
  })
  @DisplayName("同じオリジンを示す header の更新は通す")
  void passesHeaderThatIsSameOrigin(final String name, final String value)
      throws ServletException, IOException {
    final MockHttpServletRequest request = update(ORDERS);
    request.addHeader(name, value);

    doFilter(request);

    assertPassed();
  }

  @Test
  @DisplayName("Origin と Sec-Fetch-Site の両方が同じオリジンを示す更新は通す")
  void passesBothHeadersThatAreSameOrigin() throws ServletException, IOException {
    final MockHttpServletRequest request = update(ORDERS);
    request.addHeader(HttpHeaders.ORIGIN, "http://localhost");
    request.addHeader(FETCH_SITE, "same-origin");

    doFilter(request);

    assertPassed();
  }

  @Test
  @DisplayName("Origin も Sec-Fetch-Site もない更新は通し、CSRF のトークンの照合に任せる")
  void passesUpdateWithoutHeaders() throws ServletException, IOException {
    doFilter(update(ORDERS));

    assertPassed();
  }

  @Test
  @DisplayName("X-Forwarded-Host と X-Forwarded-Proto が示す公開のオリジンの Origin は通す")
  void passesPublicOriginFromForwardedHost() throws ServletException, IOException {
    final MockHttpServletRequest request = update(ORDERS);
    request.addHeader("X-Forwarded-Host", PUBLIC_HOST);
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader(HttpHeaders.ORIGIN, "https://" + PUBLIC_HOST);

    doFilter(request);

    assertPassed();
  }

  @Test
  @DisplayName(
      "ALB の形（Host と X-Forwarded-Proto と X-Forwarded-Port、X-Forwarded-Host なし）では、https の公開のオリジンを通す")
  void passesPublicOriginBehindAlb() throws ServletException, IOException {
    final MockHttpServletRequest request = albUpdate();
    request.addHeader(HttpHeaders.ORIGIN, "https://" + PUBLIC_HOST);

    doFilter(request);

    assertPassed();
  }

  @Test
  @DisplayName("ALB の形では、公開のオリジンと scheme が違う http の Origin を拒否する")
  void rejectsHttpOriginBehindAlb() throws ServletException, IOException {
    final MockHttpServletRequest request = albUpdate();
    request.addHeader(HttpHeaders.ORIGIN, "http://" + PUBLIC_HOST);

    doFilter(request);

    assertRejected();
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
  @DisplayName("GET、HEAD、OPTIONS は、別のオリジンを示しても通す")
  void passesSafeMethods(final String method) throws ServletException, IOException {
    final MockHttpServletRequest request = new MockHttpServletRequest(method, ORDERS);
    request.addHeader(HttpHeaders.ORIGIN, EVIL_ORIGIN);
    request.addHeader(FETCH_SITE, "cross-site");

    doFilter(request);

    assertPassed();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/orders", "/apis", "/logout/other"})
  @DisplayName("/api/** と /logout の外の更新は、別のオリジンを示しても通す")
  void passesUpdateOutsideTargets(final String path) throws ServletException, IOException {
    final MockHttpServletRequest request = update(path);
    request.addHeader(HttpHeaders.ORIGIN, EVIL_ORIGIN);
    request.addHeader(FETCH_SITE, "cross-site");

    doFilter(request);

    assertPassed();
  }

  private static MockHttpServletRequest update(final String path) {
    return new MockHttpServletRequest("POST", path);
  }

  /** ALB が付ける形の要求。ALB は X-Forwarded-Host を付けず、公開のホストを Host のまま送る。 */
  private static MockHttpServletRequest albUpdate() {
    final MockHttpServletRequest request = update(ORDERS);
    request.addHeader(HttpHeaders.HOST, PUBLIC_HOST);
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-Port", "443");
    return request;
  }

  /**
   * 本番の {@code server.request.header.forwarded.strategy: framework} と同じく、X-Forwarded-* を反映する filter
   * の後ろで検査する。
   */
  private void doFilter(final MockHttpServletRequest request) throws ServletException, IOException {
    new ForwardedHeaderFilter(false)
        .doFilter(
            request,
            new MockHttpServletResponse(),
            (forwarded, response) ->
                filter.doFilter(
                    (HttpServletRequest) forwarded, (HttpServletResponse) response, chain));
  }

  private void assertRejected() {
    assertThat(denied)
        .as("拒否を渡した例外")
        .singleElement()
        .isInstanceOf(CrossOriginRequestRejectedException.class);
    assertThat(chain.getRequest()).as("後ろの処理へ進んだ要求").isNull();
  }

  private void assertPassed() {
    assertThat(denied).as("拒否を渡した例外").isEmpty();
    assertThat(chain.getRequest()).as("後ろの処理へ進んだ要求").isNotNull();
  }
}
