package com.example.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * OIDC のログインの失敗を、error code だけを INFO に残して SPA の {@code /login-failed} へ移す。
 *
 * <p>{@code SimpleUrlAuthenticationFailureHandler} を使わない。その {@code saveException}
 * は、セッションがあれば例外をセッションへ書く。 コールバックの時点では認可リクエストのためのセッションが必ずあり、IdP が書いた {@code error_description}
 * を含む例外が DB のセッションに残る。
 *
 * <p>ponytail: login CSRF を起こせる攻撃者は、64 文字までの任意の token をこの INFO のログに書ける。量は配信点の rate limit で抑える。
 * 抑えきれなくなったら、既知の error code の一覧に絞る。
 */
@Slf4j
final class LoginFailureHandler implements AuthenticationFailureHandler {

  /** RFC 6749 の error code に使える文字の範囲と、ログに残す長さの上限。 */
  private static final Pattern ERROR_CODE = Pattern.compile("[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,64}");

  /** error code を取れないときの {@code error.type}。OpenTelemetry の {@code error.type} の既定値と同じ。 */
  private static final String OTHER = "_OTHER";

  /** 失敗を伝える SPA の画面。 */
  private static final String FAILURE_PATH = "/login-failed";

  /** {@code /login-failed} へ移す。 */
  private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

  @Override
  public void onAuthenticationFailure(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AuthenticationException exception)
      throws IOException {
    // error_description は IdP が書く文で、例外の message と toString() にも入るため、例外を渡さない。
    log.atInfo().addKeyValue("error.type", errorType(exception)).log("OAuth2 login failed");
    redirectStrategy.sendRedirect(request, response, FAILURE_PATH);
  }

  /** ログに残す失敗の種類。OAuth2 の error code が RFC 6749 の文字だけなら、その code を返す。 */
  /* package */ static String errorType(final AuthenticationException exception) {
    if (exception instanceof OAuth2AuthenticationException oauth2
        && ERROR_CODE.matcher(oauth2.getError().getErrorCode()).matches()) {
      return oauth2.getError().getErrorCode();
    }
    return OTHER;
  }
}
