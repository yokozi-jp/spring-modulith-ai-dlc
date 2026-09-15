package com.example.demo.error.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Problem Details の公開情報と応答ヘッダを API 契約へ正規化することを検証する。 */
class ApiProblemDetailsTest {

  @Test
  @DisplayName("about:blank では framework が生成した detail を除去する")
  void normalizeRemovesGenericFrameworkDetail() {
    final ApiProblemDetails problemDetails = new ApiProblemDetails(new StaticMessageSource());
    final ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No static resource api/missing.");

    problemDetails.normalize(problem, HttpStatus.NOT_FOUND, Locale.ENGLISH);

    assertEquals(URI.create("about:blank"), problem.getType(), "generic problem type");
    assertNull(problem.getDetail(), "framework detail を公開しないこと");
  }

  @Test
  @DisplayName("業務固有 type の明示的な detail は保持する")
  void normalizeKeepsExplicitDetailForCustomType() {
    final ApiProblemDetails problemDetails = new ApiProblemDetails(new StaticMessageSource());
    final ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "安全な業務エラーの説明");
    problem.setType(URI.create("https://api.example.test/problems/conflict"));

    problemDetails.normalize(problem, HttpStatus.CONFLICT, Locale.JAPANESE);

    assertEquals("安全な業務エラーの説明", problem.getDetail(), "業務固有の安全な detail");
  }

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
