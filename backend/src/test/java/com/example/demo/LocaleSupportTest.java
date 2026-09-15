package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/** Accept-Language の未指定と未対応言語に対する fallback を検証する。 */
class LocaleSupportTest {

  @Test
  @DisplayName("空の Accept-Language は日本語へ戻す")
  void blankAcceptLanguageUsesDefaultLocale() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, " ");

    assertEquals(Locale.JAPANESE, LocaleSupport.resolve(request), "空ヘッダの locale");
  }

  @Test
  @DisplayName("未対応言語だけなら日本語へ戻す")
  void unsupportedAcceptLanguageUsesDefaultLocale() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "fr-FR");

    assertEquals(Locale.JAPANESE, LocaleSupport.resolve(request), "未対応言語の locale");
  }
}
