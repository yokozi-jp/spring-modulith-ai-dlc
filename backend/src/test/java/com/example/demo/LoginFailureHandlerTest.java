package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/** ログに残す {@code error.type} を、Spring を起動せずに検証する。 */
class LoginFailureHandlerTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "invalid_request",
        "unauthorized_client",
        "access_denied",
        "unsupported_response_type",
        "invalid_scope",
        "insufficient_scope",
        "invalid_token",
        "server_error",
        "temporarily_unavailable",
        "invalid_client",
        "invalid_grant",
        "unsupported_grant_type",
        "unsupported_token_type",
        "invalid_redirect_uri",
        "invalid_dpop_proof",
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
        "missing_signature_verifier"
      })
  @DisplayName("既知の error code は、そのまま返す")
  void knownErrorCodeIsKept(final String code) {
    assertThat(LoginFailureHandler.errorType(new OAuth2AuthenticationException(code)))
        .isEqualTo(code);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "custom_idp_code",
        "ACCESS_DENIED"
      })
  @DisplayName("既知でない error code は、RFC 6749 の文字だけでも _OTHER にする")
  void unknownErrorCodeBecomesOther(final String code) {
    assertThat(LoginFailureHandler.errorType(new OAuth2AuthenticationException(code)))
        .isEqualTo("_OTHER");
  }

  @Test
  @DisplayName("OAuth2 の失敗でない認証の失敗は _OTHER にする")
  void nonOauth2FailureBecomesOther() {
    assertThat(LoginFailureHandler.errorType(new BadCredentialsException("access_denied")))
        .isEqualTo("_OTHER");
  }
}
