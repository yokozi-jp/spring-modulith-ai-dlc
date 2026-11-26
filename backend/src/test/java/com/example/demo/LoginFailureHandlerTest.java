package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * ログに残す {@code error.type} を、Spring を起動せずに検証する。
 *
 * <p>空と空白だけの error code は {@code OAuth2Error} のコンストラクタが拒否するので、ここでは作れない。
 */
class LoginFailureHandlerTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "access_denied",
        "authorization_request_not_found",
        "!#[]~",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      })
  @DisplayName("RFC 6749 の文字だけの 64 文字までの error code は、そのまま返す")
  void validErrorCodeIsKept(final String code) {
    assertThat(LoginFailureHandler.errorType(new OAuth2AuthenticationException(code)))
        .isEqualTo(code);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "access denied",
        "access\"denied",
        "access\\denied",
        "アクセス拒否",
        "access_denied\n"
      })
  @DisplayName("65 文字以上か、RFC 6749 の外の文字を含む error code は _OTHER にする")
  void invalidErrorCodeBecomesOther(final String code) {
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
