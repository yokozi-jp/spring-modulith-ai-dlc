package com.example.demo;

import static com.example.demo.testkit.AppUsers.oidcLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.SharedTestConfiguration;
import errorfixture.presentation.web.ErrorFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Problem Details の traceId が要求の trace ID とログの traceId に一致し、instance を持たないことを、すべての経路で検証する。 */
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.UnitTestShouldIncludeAssert"})
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      // テストの既定は tracing の export を無効にし、traceparent を読む propagator も作らない。
      "spring.test.tracing.export=true",
      "management.tracing.export.otlp.enabled=false"
    })
@AutoConfigureMockMvc
@Import({SharedTestConfiguration.class, ErrorFixture.class})
@ExtendWith(OutputCaptureExtension.class)
class ProblemDetailsTraceIdContractTest {

  /** W3C Trace Context の要求ヘッダ。 */
  private static final String TRACEPARENT = "traceparent";

  /** ECS JSON のログと Problem Details で trace ID を持つ項目。 */
  private static final String TRACE_ID = "traceId";

  /** ログの JSON を読む。 */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** サーブレットコンテナの /error への転送を通すための、起動したサーバーのポート。 */
  @LocalServerPort private int port;

  /** 新しい trace ID（32 桁の小文字の 16 進数）を作る。 */
  private static String newTraceId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  /** 標本にしない（sampled flag が 00 の）親の span を指す traceparent を作る。 */
  private static String unsampledTraceparent(final String traceId) {
    return "00-" + traceId + "-00f067aa0ba902b7-00";
  }

  private static Stream<Arguments> mvcProblems() {
    return Stream.of(
        Arguments.of(
            "MVC の 405", delete("/api/error-fixture/items").with(oidcLogin()).with(csrf()), 405),
        Arguments.of("MVC の 500", get("/api/error-fixture/unhandled").with(oidcLogin()), 500),
        Arguments.of("Spring Security の 401", get("/api/orders"), 401),
        Arguments.of("Spring Security の CSRF の 403", post("/api/orders").with(oidcLogin()), 403));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mvcProblems")
  @DisplayName(
      "sampled flag が 00 の traceparent でも、Problem Details の traceId はその trace ID になり、instance を持たない")
  void mvcProblemCarriesTraceparentTraceId(
      final String name, final MockHttpServletRequestBuilder request, final int status)
      throws Exception {
    final String traceId = newTraceId();

    mockMvc
        .perform(request.header(TRACEPARENT, unsampledTraceparent(traceId)))
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.traceId").value(traceId))
        .andExpect(jsonPath("$.instance").doesNotExist());
  }

  @Test
  @DisplayName("/error へ転送された 400 の Problem Details も、traceparent の trace ID を持ち、instance を持たない")
  void forwardedErrorCarriesTraceparentTraceId() throws Exception {
    final String traceId = newTraceId();
    // 標準にない method は Spring Security の firewall が拒否し、コンテナが /error へ転送する。
    final HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders"))
            .method("FOO", HttpRequest.BodyPublishers.noBody())
            .header(TRACEPARENT, unsampledTraceparent(traceId))
            .build();

    final HttpResponse<String> response;
    try (HttpClient client = HttpClient.newHttpClient()) {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    assertEquals(400, response.statusCode(), response::body);
    assertEquals(
        MediaType.APPLICATION_PROBLEM_JSON_VALUE,
        response.headers().firstValue("Content-Type").orElse(""),
        response::body);
    final JsonNode body = JSON.readTree(response.body());
    assertEquals(traceId, body.path(TRACE_ID).asString(), response::body);
    assertFalse(body.has("instance"), response::body);
  }

  @Test
  @DisplayName(
      "traceparent のない要求の Problem Details の traceId は、同じ要求の API client error のログの traceId と同じになる")
  void problemTraceIdMatchesLoggedTraceId(final CapturedOutput output) throws Exception {
    final String body =
        mockMvc
            .perform(delete("/api/error-fixture/items").with(oidcLogin()).with(csrf()))
            .andExpect(status().isMethodNotAllowed())
            .andReturn()
            .getResponse()
            .getContentAsString();
    final String traceId = JSON.readTree(body).path(TRACE_ID).asString();

    assertTrue(traceId.matches("^[0-9a-f]{32}$"), body);
    assertTrue(
        output
            .getAll()
            .lines()
            .filter(line -> line.startsWith("{") && line.contains("API client error"))
            .map(JSON::readTree)
            .anyMatch(line -> traceId.equals(line.path(TRACE_ID).asString())),
        () -> "traceId=" + traceId + " の API client error の JSON ログ");
  }
}
