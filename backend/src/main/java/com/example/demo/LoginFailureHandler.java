package com.example.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * OIDC のログインの失敗を、error code だけを INFO に残して SPA の {@code /login-failed} へ移す。
 *
 * <p>{@code SimpleUrlAuthenticationFailureHandler} を使わない。その {@code saveException}
 * は、セッションがあれば例外をセッションへ書く。 コールバックの時点では認可リクエストのためのセッションが必ずあり、IdP が書いた {@code error_description}
 * を含む例外が DB のセッションに残る。
 */
@Slf4j
final class LoginFailureHandler implements AuthenticationFailureHandler {

  /** OAuth2ErrorCodes（RFC 6749 の 4.1.2.1 と 5.2 を含む）、OIDC Core 1.0 の 3.1.2.6、ログインの処理が投げる code。 */
  private static final Set<String> KNOWN_ERROR_CODES =
      Set.of(
          OAuth2ErrorCodes.INVALID_REQUEST,
          OAuth2ErrorCodes.UNAUTHORIZED_CLIENT,
          OAuth2ErrorCodes.ACCESS_DENIED,
          OAuth2ErrorCodes.UNSUPPORTED_RESPONSE_TYPE,
          OAuth2ErrorCodes.INVALID_SCOPE,
          OAuth2ErrorCodes.INSUFFICIENT_SCOPE,
          OAuth2ErrorCodes.INVALID_TOKEN,
          OAuth2ErrorCodes.SERVER_ERROR,
          OAuth2ErrorCodes.TEMPORARILY_UNAVAILABLE,
          OAuth2ErrorCodes.INVALID_CLIENT,
          OAuth2ErrorCodes.INVALID_GRANT,
          OAuth2ErrorCodes.UNSUPPORTED_GRANT_TYPE,
          OAuth2ErrorCodes.UNSUPPORTED_TOKEN_TYPE,
          OAuth2ErrorCodes.INVALID_REDIRECT_URI,
          OAuth2ErrorCodes.INVALID_DPOP_PROOF,
          "interaction_required",
          "login_required",
          "account_selection_required",
          "consent_required",
          "invalid_request_uri",
          "invalid_request_object",
          "request_not_supported",
          "request_uri_not_supported",
          "registration_not_supported",
          "authorization_request_not_found",
          "client_registration_not_found",
          "invalid_state_parameter",
          "invalid_nonce",
          "invalid_id_token",
          "invalid_token_response",
          "invalid_user_info_response",
          "missing_user_info_uri",
          "missing_user_name_attribute",
          "missing_signature_verifier");

  /** error code を取れないときの {@code error.type}。OpenTelemetry の {@code error.type} の既定値と同じ。 */
  private static final String OTHER = "_OTHER";

  /** 失敗を伝える SPA の画面。 */
  private static final String FAILURE_PATH = "/login-failed";

  @Override
  public void onAuthenticationFailure(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final AuthenticationException exception)
      throws IOException {
    // error_description は IdP が書く文で、例外の message と toString() にも入るため、例外を渡さない。
    log.atInfo().addKeyValue("error.type", errorType(exception)).log("OAuth2 login failed");
    new DefaultRedirectStrategy().sendRedirect(request, response, FAILURE_PATH);
  }

  /** ログに残す失敗の種類。OAuth2 の error code が既知の code なら、その code を返す。 */
  /* package */ static String errorType(final AuthenticationException exception) {
    if (exception instanceof OAuth2AuthenticationException oauth2
        && KNOWN_ERROR_CODES.contains(oauth2.getError().getErrorCode())) {
      return oauth2.getError().getErrorCode();
    }
    return OTHER;
  }
}
