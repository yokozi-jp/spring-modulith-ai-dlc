package com.example.demo.error.presentation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

/** 例外を Problem Details へ変換し、ログを 1 か所で出し、切断とコミット済みの応答を扱うことを検証する。 */
@SuppressWarnings("PMD.TooManyStaticImports")
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

  private static ApiExceptionHandler handler() {
    final StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("problem.title.500", Locale.JAPANESE, "サーバー内部エラー");
    return new ApiExceptionHandler(new ApiProblemDetails(messages));
  }

  @Test
  @DisplayName("未処理例外は 500 を返し、ログに例外の型、発生行、cause を残す")
  void unexpectedExceptionIsLoggedWithStackTrace(final CapturedOutput output) {
    final IllegalArgumentException cause = new IllegalArgumentException("invalid order state");
    cause.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement(
              "com.example.demo.OrderRepository", "save", "OrderRepository.java", 57)
        });
    final IllegalStateException exception = new IllegalStateException("order failed", cause);
    exception.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("com.example.demo.OrderService", "create", "OrderService.java", 84)
        });

    final ResponseEntity<Object> response =
        handler()
            .handleUnexpectedException(
                exception, new ServletWebRequest(new MockHttpServletRequest()));

    assertNotNull(response, "応答");
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(), "HTTP status");
    final String log = output.getAll();
    assertTrue(log.contains("Unhandled API exception"), () -> "event 名が残ること: " + log);
    assertTrue(log.contains(IllegalStateException.class.getName()), () -> "例外の型が残ること: " + log);
    assertTrue(log.contains("OrderService.java:84"), () -> "例外の発生行が残ること: " + log);
    assertTrue(log.contains("OrderRepository.java:57"), () -> "cause の発生行が残ること: " + log);
  }

  @Test
  @DisplayName("既定ハンドラが返す 5xx も ERROR と例外の型をログに残す")
  void defaultHandlerServerErrorIsLogged(final CapturedOutput output) throws Exception {
    final ResponseEntity<Object> response =
        handler()
            .handleException(
                new HttpMessageNotWritableException("cannot write"),
                new ServletWebRequest(new MockHttpServletRequest()));

    assertNotNull(response, "応答");
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(), "HTTP status");
    final String log = output.getAll();
    assertTrue(log.contains("Unhandled API exception"), () -> "event 名が残ること: " + log);
    assertTrue(log.contains("ERROR"), () -> "ERROR で出ること: " + log);
    assertTrue(
        log.contains(HttpMessageNotWritableException.class.getName()), () -> "例外の型が残ること: " + log);
  }

  @Test
  @DisplayName("コミット済みの応答では null を返し、基底クラスの WARN を重ねない")
  void committedResponseReturnsNull(final CapturedOutput output) {
    final MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    servletResponse.setCommitted(true);

    final ResponseEntity<Object> response =
        handler()
            .handleUnexpectedException(
                new IllegalStateException("failed after commit"),
                new ServletWebRequest(new MockHttpServletRequest(), servletResponse));

    assertNull(response, "コミット済みなら本文を書かないこと");
    final String log = output.getAll();
    assertFalse(log.contains("Response already committed"), () -> "基底クラスの WARN がないこと: " + log);
  }

  @Test
  @DisplayName("クライアントの切断では null を返し、ERROR も WARN も出さない")
  void disconnectedClientIsNotLoggedAsFailure(final CapturedOutput output) {
    final ResponseEntity<Object> response =
        handler()
            .handleUnexpectedException(
                new IOException("Broken pipe"),
                new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse()));

    assertNull(response, "切断なら本文を書かないこと");
    final String log = output.getAll();
    assertFalse(log.contains("ERROR"), () -> "ERROR がないこと: " + log);
    assertFalse(log.contains("WARN"), () -> "WARN がないこと: " + log);
  }
}
