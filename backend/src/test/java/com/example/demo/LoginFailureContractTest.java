package com.example.demo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.example.demo.testkit.SharedTestConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * ログインのコールバックの失敗が、error code だけを INFO に残して SPA の {@code /login-failed} へ移ることを、実際の filter chain
 * で検証する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
// 補助のメソッドは、Exception を宣言する MockMvc.perform を呼ぶ。
@SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.TooManyStaticImports"})
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

    final List<ILoggingEvent> logs = new ArrayList<>();
    final ResultActions callback =
        captureLogs(
            logs,
            () ->
                mockMvc.perform(
                    get(CALLBACK)
                        .cookie(session)
                        .param(OAuth2ParameterNames.ERROR, "access_denied")
                        .param(OAuth2ParameterNames.ERROR_DESCRIPTION, description)
                        .param(OAuth2ParameterNames.STATE, state)));

    callback.andExpect(status().isFound()).andExpect(redirectedUrl("/login-failed"));
    final Session stored =
        Objects.requireNonNull(sessionRepository.findById(sessionId(session)), "コールバックの後のセッションがない");
    assertThat(stored.getAttributeNames())
        .as("コールバックの後のセッションの属性")
        .doesNotContain(WebAttributes.AUTHENTICATION_EXCEPTION);
    assertFailureLogged(logs, "access_denied");
    assertThat(logs)
        .as("コールバックの間の INFO 以上のログ")
        .noneSatisfy(log -> assertThat(textOf(log)).contains(description));
  }

  @Test
  @DisplayName("認可リクエストを持つセッションのないコールバックは /login-failed へ移り、その error code を INFO に残す")
  void callbackWithoutAuthorizationRequestRedirectsToLoginFailed() throws Exception {
    final List<ILoggingEvent> logs = new ArrayList<>();
    final ResultActions callback =
        captureLogs(
            logs,
            () ->
                mockMvc.perform(
                    get(CALLBACK)
                        .param(OAuth2ParameterNames.CODE, "x")
                        .param(OAuth2ParameterNames.STATE, "y")));

    callback.andExpect(status().isFound()).andExpect(redirectedUrl("/login-failed"));
    assertFailureLogged(logs, "authorization_request_not_found");
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

  private static void assertFailureLogged(final List<ILoggingEvent> logs, final String errorType) {
    final List<ILoggingEvent> failures =
        logs.stream().filter(log -> EVENT.equals(log.getMessage())).toList();
    assertThat(failures).as("event=%s の記録", EVENT).hasSize(1);
    final ILoggingEvent failure = failures.getFirst();
    assertThat(failure.getLevel()).as("レベル").isEqualTo(Level.INFO);
    assertThat(failure.getThrowableProxy()).as("例外").isNull();
    final Map<String, @Nullable Object> keyValues =
        failure.getKeyValuePairs().stream()
            .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    assertThat(keyValues).as("属性").isEqualTo(Map.of("error.type", errorType));
  }

  /** 記録の message、属性、例外の message をつないだ文字列。 */
  private static String textOf(final ILoggingEvent log) {
    final StringBuilder text = new StringBuilder(log.getFormattedMessage());
    if (log.getKeyValuePairs() != null) {
      log.getKeyValuePairs().forEach(pair -> text.append(' ').append(pair.value));
    }
    for (IThrowableProxy cause = log.getThrowableProxy(); cause != null; cause = cause.getCause()) {
      text.append(' ').append(cause.getMessage());
    }
    return text.toString();
  }

  /** root の logger に appender を付けて要求を送り、INFO 以上の記録を {@code logs} に入れる。 */
  // 記録を書くのではなく、root の logger に appender を付けて読むため、LoggerFactory を使う。
  @SuppressWarnings("PMD.UseLombokSlf4j")
  private static ResultActions captureLogs(
      final List<ILoggingEvent> logs, final ThrowingRequest request) throws Exception {
    // key-value はコンソールの書式によって出ないため、Logback の記録そのものを読む。
    final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      return request.perform();
    } finally {
      root.detachAppender(appender);
      logs.addAll(appender.list);
    }
  }

  /** 例外を投げうる要求の送信。 */
  @FunctionalInterface
  private interface ThrowingRequest {
    /** 要求を送り、応答を返す。 */
    ResultActions perform() throws Exception;
  }
}
