package com.example.demo;

import static com.example.demo.testkit.AppUsers.oidcLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Problem Details の traceId が、sampled flag が 00 の traceparent の trace ID になることを、advice、Spring
 * Security、{@code /error} への転送の経路で検証する。
 *
 * <p>実際のポートの要求は、RequestContextFilter が Spring Security より前に要求を束縛し、ServerHttpObservationFilter が
 * REQUEST の dispatch で observation を要求の属性に置くことも固定する。MockMvc は要求を自分で束縛し、コンテナの /error
 * への転送もしないので、これらを示せない。
 */
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
class ProblemDetailsTraceIdContractTest {

  /** W3C Trace Context の要求ヘッダ。 */
  private static final String TRACEPARENT = "traceparent";

  /** 応答の本文を読む。 */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** サーブレットコンテナの filter の順と /error への転送を通すための、起動したサーバーのポート。 */
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
            "advice の 405", delete("/api/error-fixture/items").with(oidcLogin()).with(csrf()), 405),
        Arguments.of("Spring Security の 401", get("/api/orders"), 401),
        Arguments.of("CSRF トークンのない更新の 403", post("/api/orders").with(oidcLogin()), 403));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mvcProblems")
  @DisplayName("sampled flag が 00 の traceparent でも、Problem Details の traceId はその trace ID になる")
  void mvcProblemCarriesTraceparentTraceId(
      final String name, final MockHttpServletRequestBuilder request, final int expectedStatus)
      throws Exception {
    final String traceId = newTraceId();

    mockMvc
        .perform(request.header(TRACEPARENT, unsampledTraceparent(traceId)))
        .andExpect(status().is(expectedStatus))
        .andExpect(jsonPath("$.traceId").value(traceId))
        .andExpect(jsonPath("$.instance").doesNotExist());
  }

  private static Stream<Arguments> containerProblems() {
    return Stream.of(
        Arguments.of("Spring Security の 401", "GET", 401),
        Arguments.of("CSRF トークンのない更新の 403", "POST", 403),
        // 標準にない method は Spring Security の firewall が拒否し、コンテナが /error へ転送する。
        Arguments.of("/error へ転送された 400", "FOO", 400));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("containerProblems")
  @DisplayName("実際のポートの要求でも、Problem Details は traceparent の trace ID を持ち、instance を持たない")
  void containerProblemCarriesTraceparentTraceId(
      final String name, final String method, final int expectedStatus) throws Exception {
    final String traceId = newTraceId();
    final HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .header(TRACEPARENT, unsampledTraceparent(traceId))
            .build();

    final HttpResponse<String> response;
    try (HttpClient client = HttpClient.newHttpClient()) {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    assertEquals(expectedStatus, response.statusCode(), response::body);
    assertEquals(
        MediaType.APPLICATION_PROBLEM_JSON_VALUE,
        response.headers().firstValue("Content-Type").orElse(""),
        response::body);
    final JsonNode body = JSON.readTree(response.body());
    assertEquals(traceId, body.path("traceId").asString(), response::body);
    assertFalse(body.has("instance"), response::body);
  }
}
