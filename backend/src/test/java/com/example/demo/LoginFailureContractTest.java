package com.example.demo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.CapturedLogRecords;
import com.example.demo.testkit.ReinstallLogAppenderExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import jakarta.servlet.http.Cookie;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.WebAttributes;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * ログインのコールバックの失敗が、error code だけを INFO に残して SPA の {@code /login-failed} へ移ることを、実際の filter chain
 * で検証する。
 */
@ExtendWith(ReinstallLogAppenderExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
@SuppressWarnings("PMD.TooManyStaticImports")
class LoginFailureContractTest {

  /** ログインのコールバック。 */
  private static final String CALLBACK = "/login/oauth2/code/web";

  /** 埋め込みサーバのない MockMvc での、Spring Session の Cookie の名前。 */
  private static final String SESSION_COOKIE = "SESSION";

  /** 失敗を記録する event 名。 */
  private static final String EVENT = "OAuth2 login failed";

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** コールバックの後のセッションを読む JDBC のリポジトリ。 */
  @Autowired private JdbcIndexedSessionRepository sessionRepository;

  /** OTLP へ送る直前の LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

  @Test
  @DisplayName("IdP のエラーのコールバックは /login-failed へ移り、error code だけを INFO に残し、error_description を残さない")
  void idpErrorRedirectsToLoginFailed() throws Exception {
    final MockHttpServletResponse start =
        mockMvc
            .perform(get("/oauth2/authorization/web"))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse();
    final Cookie session =
        Objects.requireNonNull(start.getCookie(SESSION_COOKIE), "認可の開始でセッションの Cookie が来ない");
    final String state = state(Objects.requireNonNull(start.getHeader(HttpHeaders.LOCATION)));
    final String description = "marker-" + UUID.randomUUID();
    final int failuresBefore = capturedLogRecords.withBody(EVENT).size();

    mockMvc
        .perform(
            get(CALLBACK)
                .cookie(session)
                .param(OAuth2ParameterNames.ERROR, "access_denied")
                .param(OAuth2ParameterNames.ERROR_DESCRIPTION, description)
                .param(OAuth2ParameterNames.STATE, state))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/login-failed"));

    final Session stored =
        Objects.requireNonNull(sessionRepository.findById(sessionId(session)), "コールバックの後のセッションがない");
    assertThat(stored.getAttributeNames())
        .as("コールバックの後のセッションの属性")
        .doesNotContain(WebAttributes.AUTHENTICATION_EXCEPTION);
    assertFailureLogged(failuresBefore, "access_denied");
    assertThat(capturedLogRecords.all())
        .as("LogRecord")
        .noneSatisfy(record -> assertThat(record.toString()).contains(description));
  }

  @Test
  @DisplayName("認可リクエストを持つセッションのないコールバックは /login-failed へ移り、その error code を INFO に残す")
  void callbackWithoutAuthorizationRequestRedirectsToLoginFailed() throws Exception {
    final int failuresBefore = capturedLogRecords.withBody(EVENT).size();

    mockMvc
        .perform(
            get(CALLBACK)
                .param(OAuth2ParameterNames.CODE, "x")
                .param(OAuth2ParameterNames.STATE, "y"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/login-failed"));

    assertFailureLogged(failuresBefore, "authorization_request_not_found");
  }

  /** IdP へ移す Location から、セッションに保存した認可リクエストの state を読む。 */
  private static String state(final String location) {
    return UriUtils.decode(
        Objects.requireNonNull(
            UriComponentsBuilder.fromUriString(location)
                .build()
                .getQueryParams()
                .getFirst(OAuth2ParameterNames.STATE)),
        UTF_8);
  }

  private static String sessionId(final Cookie session) {
    return new String(Base64.getDecoder().decode(session.getValue()), UTF_8);
  }

  private void assertFailureLogged(final int failuresBefore, final String errorType) {
    final List<LogRecordData> failures = capturedLogRecords.withBody(EVENT);
    assertThat(failures).as("event=%s の記録", EVENT).hasSize(failuresBefore + 1);
    final LogRecordData failure = failures.getLast();
    assertThat(failure.getSeverity()).as("レベル").isEqualTo(Severity.INFO);
    assertThat(failure.getAttributes())
        .as("属性")
        .isEqualTo(Attributes.of(AttributeKey.stringKey("error.type"), errorType));
  }
}
