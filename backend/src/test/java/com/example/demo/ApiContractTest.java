package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.SharedTestConfiguration;
import conflictfixture.ConflictFixture;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** API の Problem Details、locale、ブラウザ向けセキュリティヘッダを HTTP レベルで検証する。 */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
@SpringBootTest
@AutoConfigureMockMvc
@Import({SharedTestConfiguration.class, ConflictFixture.class})
class ApiContractTest {

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("言語未指定の未認証 API は日本語の 401 Problem Details を返す")
  void unauthenticatedApiRequestReturnsDefaultLocaleProblemDetails() throws Exception {
    mockMvc
        .perform(get("/api/missing"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "ja"))
        .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Session realm=\"demo\""))
        .andExpect(jsonPath("$.title").value("認証が必要です"))
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  @DisplayName("/error へ転送された 401 にも WWW-Authenticate の challenge を付ける")
  void errorEndpointAddsChallengeToUnauthorized() throws Exception {
    mockMvc
        .perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 401))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Session realm=\"demo\""))
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  @DisplayName("地域付き英語を指定した CSRF エラーは英語の 403 Problem Details を返す")
  void csrfFailureReturnsEnglishProblemDetails() throws Exception {
    mockMvc
        .perform(
            post("/api/missing")
                .with(user("test-user"))
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "en"))
        .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  @DisplayName("品質値付き Accept-Language から対応言語を選ぶ")
  void missingAuthenticatedApiResourceUsesPreferredSupportedLocale() throws Exception {
    mockMvc
        .perform(
            get("/api/missing")
                .with(user("test-user"))
                .header(HttpHeaders.ACCEPT_LANGUAGE, "fr-FR, en;q=0.9"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "en"))
        .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.detail").doesNotExist());
  }

  @Test
  @DisplayName("/error は日本語でも実装詳細を含まない Problem Details を返す")
  void errorEndpointReturnsLocalizedProblemDetailsWithoutImplementationDetails() throws Exception {
    mockMvc
        .perform(
            get("/error")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "ja-JP")
                .requestAttr(
                    RequestDispatcher.ERROR_STATUS_CODE,
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR.value())
                .requestAttr(
                    RequestDispatcher.ERROR_EXCEPTION,
                    new IllegalStateException("sensitive implementation detail")))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "ja"))
        .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
        .andExpect(jsonPath("$.title").value("サーバー内部エラー"))
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.detail").doesNotExist())
        .andExpect(jsonPath("$.exception").doesNotExist())
        .andExpect(jsonPath("$.trace").doesNotExist());
  }

  @Test
  @DisplayName("ConflictException は日本語の 409 Problem Details になり、例外の内容を含まない")
  void conflictExceptionReturnsProblemDetailsWithoutImplementationDetails() throws Exception {
    final String body =
        mockMvc
            .perform(get("/api/conflict-fixture/stale").with(user("test-user")))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "ja"))
            .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE))
            .andExpect(jsonPath("$.type").value("about:blank"))
            .andExpect(jsonPath("$.title").value("競合が発生しました"))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.detail").doesNotExist())
            .andExpect(jsonPath("$.exception").doesNotExist())
            .andExpect(jsonPath("$.trace").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("O-1", "ConflictException", ".java");
  }

  @Test
  @DisplayName("行ロックを待ち切れなかった ConflictException は英語の 409 になり、原因の内容を含まない")
  void lockedConflictExceptionReturnsEnglishProblemDetailsWithoutCause() throws Exception {
    final String body =
        mockMvc
            .perform(
                get("/api/conflict-fixture/locked")
                    .with(user("test-user"))
                    .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US"))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "en"))
            .andExpect(jsonPath("$.type").value("about:blank"))
            .andExpect(jsonPath("$.title").value("Conflict"))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.detail").doesNotExist())
            .andExpect(jsonPath("$.exception").doesNotExist())
            .andExpect(jsonPath("$.trace").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .doesNotContain("t_x", "secret_sql", "CannotAcquireLockException", "ConflictException")
        .doesNotContain(".java");
  }

  @Test
  @DisplayName("ConflictException ではない IllegalStateException は 409 にならず 500 のままである")
  void illegalStateExceptionStaysInternalServerError() throws Exception {
    mockMvc
        .perform(get("/api/conflict-fixture/illegal-state").with(user("test-user")))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500));
  }

  @Test
  @DisplayName("/error は Accept が text/html でも転送元の status の Problem Details を返す")
  void errorEndpointIgnoresHtmlAccept() throws Exception {
    mockMvc
        .perform(
            get("/error")
                .accept(MediaType.TEXT_HTML)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 400))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("リクエストが不正です"))
        .andExpect(jsonPath("$.status").value(400));
  }

  @ParameterizedTest
  @CsvSource({
    "413, ja, リクエストが大きすぎます",
    "413, en, Content Too Large",
    "422, ja, 処理できない内容です",
    "422, en, Unprocessable Content",
    "429, ja, リクエストが多すぎます",
    "429, en, Too Many Requests",
    "503, ja, サービスを利用できません",
    "503, en, Service Unavailable"
  })
  @DisplayName("/error は 413、422、429、503 の title を日本語と英語で返す")
  void errorEndpointLocalizesAdditionalStatusTitles(
      final int code, final String language, final String title) throws Exception {
    mockMvc
        .perform(
            get("/error")
                .header(HttpHeaders.ACCEPT_LANGUAGE, language)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, code))
        .andExpect(status().is(code))
        .andExpect(jsonPath("$.title").value(title));
  }

  @Test
  @DisplayName("別オリジンのブラウザクライアントを許可しない")
  void crossOriginBrowserClientIsNotAllowed() throws Exception {
    mockMvc
        .perform(get("/api/missing").header("Origin", "https://client.example"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  @DisplayName("ブラウザ向けセキュリティヘッダを明示的に返す")
  void browserSecurityHeadersAreExplicit() throws Exception {
    mockMvc
        .perform(get("/error").secure(true))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
        .andExpect(
            header()
                .string(
                    "Permissions-Policy",
                    "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(
            header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
  }
}
