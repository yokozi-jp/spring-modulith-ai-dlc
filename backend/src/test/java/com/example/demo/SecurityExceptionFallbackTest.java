package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * API の entry point と access denied handler が使う {@code resolveOrSendError} を、Spring を起動せずに検証する。
 *
 * <p>sendError の後の {@code /error} への転送と、そこで返す Problem Details と {@code WWW-Authenticate} は {@code
 * ApiContractTest} が確かめる。
 */
class SecurityExceptionFallbackTest {

  /** resolver が受け取った例外。呼ばれたことの確認に使う。 */
  private final List<Exception> resolved = new ArrayList<>();

  /** どの resolver も例外を扱わなかった状態を模す。 */
  private final HandlerExceptionResolver unresolving =
      (request, response, handler, exception) -> {
        resolved.add(exception);
        return null;
      };

  /** 検証対象の logger に appender を付けて処理を実行し、記録を返す。 */
  // 記録を書くのではなく、検証対象の logger に appender を付けて読むため、LoggerFactory を使う。
  @SuppressWarnings("PMD.UseLombokSlf4j")
  private static List<ILoggingEvent> captureLogs(final ThrowingRunnable action) throws IOException {
    // key-value はコンソールの書式によって出ないため、Logback の記録そのものを読む。
    final Logger logger = (Logger) LoggerFactory.getLogger(SecurityConfig.class);
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      action.run();
    } finally {
      logger.detachAppender(appender);
    }
    return appender.list;
  }

  private static void assertWarned(
      final List<ILoggingEvent> logs,
      final String event,
      final Exception exception,
      final int status) {
    assertEquals(1, logs.size(), "記録の件数");
    final ILoggingEvent log = logs.getFirst();
    assertEquals(event, log.getMessage(), "event 名");
    assertEquals(Level.WARN, log.getLevel(), "レベル");
    assertNotNull(log.getThrowableProxy(), "例外");
    assertEquals(exception.getClass().getName(), log.getThrowableProxy().getClassName(), "例外の型");
    final Map<String, Object> keyValues =
        log.getKeyValuePairs().stream()
            .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    assertEquals(Map.of("http.response.status_code", status), keyValues, "属性");
  }

  @Test
  @DisplayName("resolver が扱わなかった未認証は WARN を残して 401 で sendError する")
  void unresolvedAuthenticationSendsUnauthorized() throws IOException {
    final InsufficientAuthenticationException exception =
        new InsufficientAuthenticationException("unauthenticated");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    final List<ILoggingEvent> logs =
        captureLogs(
            () ->
                SecurityConfig.resolveOrSendError(
                    unresolving,
                    new MockHttpServletRequest(),
                    response,
                    exception,
                    HttpStatus.UNAUTHORIZED));

    assertEquals(List.of(exception), resolved, "resolver へ渡した例外");
    assertEquals(401, response.getStatus(), "sendError の status");
    assertWarned(logs, "Unresolved API security exception", exception, 401);
  }

  @Test
  @DisplayName("resolver が扱わなかった認可の拒否は WARN を残して 403 で sendError する")
  void unresolvedAccessDeniedSendsForbidden() throws IOException {
    final AccessDeniedException exception = new AccessDeniedException("denied");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    final List<ILoggingEvent> logs =
        captureLogs(
            () ->
                SecurityConfig.resolveOrSendError(
                    unresolving,
                    new MockHttpServletRequest(),
                    response,
                    exception,
                    HttpStatus.FORBIDDEN));

    assertEquals(List.of(exception), resolved, "resolver へ渡した例外");
    assertEquals(403, response.getStatus(), "sendError の status");
    assertWarned(logs, "Unresolved API security exception", exception, 403);
  }

  @Test
  @DisplayName("resolver が扱った例外では sendError もログも出さない")
  void resolvedExceptionIsLeftToResolver() throws IOException {
    final AccessDeniedException exception = new AccessDeniedException("denied");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    final List<ILoggingEvent> logs =
        captureLogs(
            () ->
                SecurityConfig.resolveOrSendError(
                    (request, servletResponse, handler, ex) -> new ModelAndView(),
                    new MockHttpServletRequest(),
                    response,
                    exception,
                    HttpStatus.FORBIDDEN));

    assertEquals(200, response.getStatus(), "status を変えないこと");
    assertNull(response.getErrorMessage(), "sendError しないこと");
    assertEquals(List.of(), logs, "記録");
  }

  @Test
  @DisplayName("commit 済みの応答では sendError せず、commit 後であることを WARN で残す")
  void committedResponseIsNotSentError() throws IOException {
    final InsufficientAuthenticationException exception =
        new InsufficientAuthenticationException("unauthenticated");
    final MockHttpServletResponse response = new MockHttpServletResponse();
    response.setCommitted(true);

    // commit 済みで sendError すると MockHttpServletResponse は IllegalStateException を投げる。
    final List<ILoggingEvent> logs =
        captureLogs(
            () ->
                SecurityConfig.resolveOrSendError(
                    unresolving,
                    new MockHttpServletRequest(),
                    response,
                    exception,
                    HttpStatus.UNAUTHORIZED));

    assertEquals(List.of(exception), resolved, "resolver へ渡した例外");
    assertNull(response.getErrorMessage(), "sendError しないこと");
    assertWarned(logs, "Unresolved API security exception after commit", exception, 401);
  }

  /** 検査例外を投げる処理。 */
  @FunctionalInterface
  private interface ThrowingRunnable {
    /** 処理を実行する。 */
    void run() throws IOException;
  }
}
