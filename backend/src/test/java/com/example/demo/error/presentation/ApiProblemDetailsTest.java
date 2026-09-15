package com.example.demo.error.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Problem Details の応答ヘッダが既存の cache variation を保持することを検証する。 */
class ApiProblemDetailsTest {

  @Test
  @DisplayName("既存の Vary へ Accept-Language を追記する")
  void responseHeadersAppendAcceptLanguageToVary() {
    final HttpHeaders original = new HttpHeaders();
    original.setVary(List.of(HttpHeaders.ORIGIN));

    final HttpHeaders actual = ApiProblemDetails.responseHeaders(original, Locale.JAPANESE);

    assertEquals(
        List.of(HttpHeaders.ORIGIN, HttpHeaders.ACCEPT_LANGUAGE),
        actual.getVary(),
        "Vary の request header 一覧");
    assertEquals(List.of(HttpHeaders.ORIGIN), original.getVary(), "元ヘッダを変更しないこと");
  }

  @Test
  @DisplayName("Vary に Accept-Language があれば重複させない")
  void responseHeadersDoNotDuplicateAcceptLanguage() {
    final HttpHeaders original = new HttpHeaders();
    original.setVary(List.of(HttpHeaders.ORIGIN, "accept-language"));

    final HttpHeaders actual = ApiProblemDetails.responseHeaders(original, Locale.ENGLISH);

    assertEquals(
        List.of(HttpHeaders.ORIGIN, "accept-language"),
        actual.getVary(),
        "Accept-Language を大文字小文字を区別せず重複排除すること");
  }

  @Test
  @DisplayName("Vary がアスタリスクなら具体的なヘッダ名を追加しない")
  void responseHeadersKeepWildcardVary() {
    final HttpHeaders original = new HttpHeaders();
    original.setVary(List.of("*"));

    final HttpHeaders actual = ApiProblemDetails.responseHeaders(original, Locale.JAPANESE);

    assertEquals(List.of("*"), actual.getVary(), "Vary のアスタリスクを維持すること");
  }
}
