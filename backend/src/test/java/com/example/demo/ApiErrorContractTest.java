package com.example.demo;

import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.CapturedLogRecords;
import com.example.demo.testkit.SharedTestConfiguration;
import errorfixture.presentation.web.ErrorFixture;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** テスト専用の Controller で、入力検証、未処理例外、Spring MVC の標準例外の Problem Details とログを検証する。 */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
@SpringBootTest
@AutoConfigureMockMvc
@Import({SharedTestConfiguration.class, ErrorFixture.class})
class ApiErrorContractTest {

  /** 入力検証エラーの problem type。 */
  private static final String VALIDATION_ERROR = "/problems/validation-error";

  /** 入力値を含む本文。応答に入力値が出ないことを確かめる。 */
  private static final String INVALID_BODY =
      "{\"name\":\"\",\"items\":[{\"code\":\"secret-value\"}]}";

  /** ログの属性名。 */
  private static final AttributeKey<Long> STATUS_CODE =
      AttributeKey.longKey("http.response.status_code");

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** OTLP へ送る直前の LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

  /** このコンテキストの OpenTelemetry。 */
  @Autowired private OpenTelemetry openTelemetry;

  @BeforeEach
  void installAppender() {
    // 別のコンテキストが appender の送り先を差し替えていても、このコンテキストの記録を読む。
    OpenTelemetryAppender.install(openTelemetry);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("problemCases")
  @DisplayName("各エラーは期待した status、type、日本語の title の Problem Details を detail なしで返す")
  void errorsReturnProblemDetails(
      final String name,
      final MockHttpServletRequestBuilder request,
      final int status,
      final String type,
      final String title)
      throws Exception {
    mockMvc
        .perform(request.with(user("test-user")).header(HttpHeaders.ACCEPT_LANGUAGE, "ja"))
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(type))
        .andExpect(jsonPath("$.title").value(title))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.detail").doesNotExist());
  }

  private static Stream<Arguments> problemCases() {
    final String validation = "入力内容に誤りがあります";
    final String badRequest = "リクエストが不正です";
    return Stream.of(
        Arguments.of("本文の入力検証", invalidBody(), 400, VALIDATION_ERROR, validation),
        Arguments.of(
            "パラメータの入力検証",
            get("/api/error-fixture/items?limit=0"),
            400,
            VALIDATION_ERROR,
            validation),
        Arguments.of(
            "ConstraintViolation",
            get("/api/error-fixture/constraint-violation"),
            400,
            VALIDATION_ERROR,
            validation),
        Arguments.of("未処理例外", get("/api/error-fixture/unhandled"), 500, "about:blank", "サーバー内部エラー"),
        Arguments.of(
            "405",
            delete("/api/error-fixture/items").with(csrf()),
            405,
            "about:blank",
            "許可されていないメソッドです"),
        Arguments.of(
            "415",
            postItems().contentType(MediaType.TEXT_PLAIN).content("x"),
            415,
            "about:blank",
            "対応していないメディアタイプです"),
        Arguments.of(
            "JSON の構文エラー",
            postItems().contentType(MediaType.APPLICATION_JSON).content("{"),
            400,
            "about:blank",
            badRequest),
        Arguments.of(
            "型の不一致", get("/api/error-fixture/items?limit=abc"), 400, "about:blank", badRequest));
  }

  @Test
  @DisplayName("入力検証エラーの title は英語でも返る")
  void validationProblemTitleIsEnglish() throws Exception {
    mockMvc
        .perform(invalidBody().with(user("test-user")).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
        .andExpect(jsonPath("$.title").value("Invalid request content"));
  }

  @Test
  @DisplayName("本文の入力検証エラーは本文の root を起点にした pointer と文言を返し、入力値を含まない")
  void bodyValidationErrorsHavePointers() throws Exception {
    mockMvc
        .perform(invalidBody().with(user("test-user")).header(HttpHeaders.ACCEPT_LANGUAGE, "ja"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(2))
        .andExpect(jsonPath("$.errors[0].pointer").value("/items/0/code"))
        .andExpect(jsonPath("$.errors[0].detail").value("長さは 0 以上 3 以下で入力してください。"))
        .andExpect(jsonPath("$.errors[1].pointer").value("/name"))
        .andExpect(jsonPath("$.errors[1].detail").value("この項目は必須です。"))
        .andExpect(content().string(not(Matchers.containsString("secret-value"))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("methodValidationCases")
  @DisplayName("メソッド検証の誤りは引数を起点にした RFC 6901 の pointer を返す")
  void methodValidationErrorsHavePointers(
      final String name, final MockHttpServletRequestBuilder request, final List<String> pointers)
      throws Exception {
    mockMvc
        .perform(request.with(user("test-user")).header(HttpHeaders.ACCEPT_LANGUAGE, "ja"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
        .andExpect(jsonPath("$.errors[*].pointer").value(Matchers.contains(pointers.toArray())))
        .andExpect(jsonPath("$.errors[*].detail").value(Matchers.everyItem(not(""))));
  }

  private static Stream<Arguments> methodValidationCases() {
    return Stream.of(
        Arguments.of("パラメータ", get("/api/error-fixture/items?limit=0"), List.of("/limit")),
        Arguments.of(
            "本文とほかの制約のある引数",
            post("/api/error-fixture/items-with-limit?limit=1")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"items\":[{\"code\":\"too-long\"}]}"),
            List.of("/items/0/code")),
        Arguments.of(
            "リストのクエリパラメータの要素",
            get("/api/error-fixture/names").queryParam("names", "", "ok"),
            List.of("/names/0")),
        // Spring MVC は引数をまたぐ違反だけでは例外にしない（spring-framework#33271）。
        // 引数の違反（to < 0）と一緒に起こし、引数をまたぐ違反が "" になることを確かめる。
        Arguments.of("引数をまたぐ制約", get("/api/error-fixture/range?from=2&to=-1"), List.of("", "/to")));
  }

  @Test
  @DisplayName("ConstraintViolationException は同じ形の errors を返し、入力値を含まない")
  void constraintViolationErrorsHavePointers() throws Exception {
    mockMvc
        .perform(
            get("/api/error-fixture/constraint-violation")
                .with(user("test-user"))
                .header(HttpHeaders.ACCEPT_LANGUAGE, "ja"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.length()").value(1))
        .andExpect(jsonPath("$.errors[0].pointer").value("/code"))
        .andExpect(jsonPath("$.errors[0].detail").value("長さは 0 以上 3 以下で入力してください。"))
        .andExpect(content().string(not(Matchers.containsString("too-long-code"))));
  }

  @Test
  @DisplayName("422 の ResponseStatusException は 500 の経路でなく標準の処理で返り、reason のテーブルとキーを含まない")
  void unprocessableContentHidesReason() throws Exception {
    mockMvc
        .perform(
            get("/api/error-fixture/unprocessable")
                .with(user("test-user"))
                .header(HttpHeaders.ACCEPT_LANGUAGE, "ja"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.title").value("処理できない内容です"))
        .andExpect(jsonPath("$.detail").doesNotExist())
        .andExpect(content().string(not(Matchers.containsString("fixture_child"))))
        .andExpect(content().string(not(Matchers.containsString("secret-key"))));
  }

  @Test
  @DisplayName("JSON の構文エラーと型の不一致は errors を持たない")
  void malformedRequestsHaveNoErrors() throws Exception {
    mockMvc
        .perform(
            postItems()
                .with(user("test-user"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors").doesNotExist());
    mockMvc
        .perform(get("/api/error-fixture/items?limit=abc").with(user("test-user")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  @Test
  @DisplayName("入力検証は WARN、その他の 4xx は INFO、未処理例外は ERROR で、status 以外の key-value を持たない")
  void failuresAreLoggedWithStatusOnly() throws Exception {
    mockMvc.perform(invalidBody().with(user("test-user"))).andExpect(status().isBadRequest());
    assertLogged("API validation failed", Severity.WARN, 400, Set.of());

    mockMvc
        .perform(postItems().with(user("test-user")).contentType(MediaType.TEXT_PLAIN).content("x"))
        .andExpect(status().isUnsupportedMediaType());
    assertLogged("API client error", Severity.INFO, 415, Set.of());

    final int errorsBefore = capturedLogRecords.withBody("Unhandled API exception").size();
    mockMvc
        .perform(get("/api/error-fixture/unhandled").with(user("test-user")))
        .andExpect(status().isInternalServerError());
    assertEquals(
        errorsBefore + 1,
        capturedLogRecords.withBody("Unhandled API exception").size(),
        "catch-all の 500 で ERROR を 1 件だけ出すこと");
    final LogRecordData error =
        assertLogged(
            "Unhandled API exception",
            Severity.ERROR,
            500,
            Set.of("exception.type", "exception.message", "exception.stacktrace"));
    assertEquals(
        IllegalStateException.class.getName(),
        error.getAttributes().get(AttributeKey.stringKey("exception.type")),
        "例外の型");
  }

  @Test
  @DisplayName("405 で PageNotFound の WARN を記録しない")
  void methodNotAllowedDoesNotLogPageNotFound() throws Exception {
    mockMvc
        .perform(delete("/api/error-fixture/items").with(user("test-user")).with(csrf()))
        .andExpect(status().isMethodNotAllowed());

    assertTrue(
        capturedLogRecords.withScope("org.springframework.web.servlet.PageNotFound").isEmpty(),
        "PageNotFound の記録がないこと");
  }

  private LogRecordData assertLogged(
      final String event, final Severity severity, final long status, final Set<String> others) {
    final LogRecordData record = capturedLogRecords.withBody(event).getLast();
    assertEquals(severity, record.getSeverity(), event + " のレベル");
    assertEquals(status, record.getAttributes().get(STATUS_CODE), event + " の status");
    final Set<String> expected =
        Stream.concat(Stream.of(STATUS_CODE.getKey()), others.stream()).collect(Collectors.toSet());
    final Set<String> actual =
        record.getAttributes().asMap().keySet().stream()
            .map(AttributeKey::getKey)
            .collect(Collectors.toSet());
    assertEquals(expected, actual, event + " の属性");
    return record;
  }

  private static MockHttpServletRequestBuilder postItems() {
    return post("/api/error-fixture/items").with(csrf());
  }

  private static MockHttpServletRequestBuilder invalidBody() {
    return postItems().contentType(MediaType.APPLICATION_JSON).content(INVALID_BODY);
  }
}
