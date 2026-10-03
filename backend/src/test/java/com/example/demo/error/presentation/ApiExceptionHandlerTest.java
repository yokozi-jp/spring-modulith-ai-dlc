package com.example.demo.error.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.demo.error.ResourceConflictException;
import com.example.demo.error.ResourceNotFoundException;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * 未処理例外を汎用の 500 応答へ変換し、調査用の例外の詳細をログへ残すことと、競合と未検出の例外を 409 と 404 へ変換することを検証する。
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

  @Test
  @DisplayName("未処理例外は 500 を返し、ログに例外の型、発生行、cause を残す")
  void unexpectedExceptionIsLoggedWithStackTrace(final CapturedOutput output) {
    final StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("problem.title.500", Locale.JAPANESE, "サーバー内部エラー");
    final ApiExceptionHandler handler = new ApiExceptionHandler(new ApiProblemDetails(messages));
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
        handler.handleUnexpectedException(
            exception, new ServletWebRequest(new MockHttpServletRequest()));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(), "HTTP status");
    final String log = output.getAll();
    assertTrue(log.contains("Unhandled API exception"), () -> "event 名が残ること: " + log);
    assertTrue(log.contains(IllegalStateException.class.getName()), () -> "例外の型が残ること: " + log);
    assertTrue(log.contains("OrderService.java:84"), () -> "例外の発生行が残ること: " + log);
    assertTrue(log.contains("OrderRepository.java:57"), () -> "cause の発生行が残ること: " + log);
  }

  @Test
  @DisplayName("競合の例外は 409 を返し、例外のメッセージを detail に出さない")
  void resourceConflictIsConvertedTo409() {
    final StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("problem.title.409", Locale.JAPANESE, "競合が発生しました");
    final ApiExceptionHandler handler = new ApiExceptionHandler(new ApiProblemDetails(messages));

    final ResponseEntity<Object> response =
        handler.handleResourceConflictException(
            new ResourceConflictException("lock_no mismatch: table=t_item"),
            new ServletWebRequest(new MockHttpServletRequest()));

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode(), "HTTP status");
    final ProblemDetail problem =
        assertInstanceOf(ProblemDetail.class, response.getBody(), "body の型");
    assertEquals(409, problem.getStatus(), "problem の status");
    assertEquals("競合が発生しました", problem.getTitle(), "ロケールに合わせた title");
    assertNull(problem.getDetail(), "内部のメッセージを detail に出さないこと");
  }

  @Test
  @DisplayName("未検出の例外は 404 を返し、例外のメッセージを detail に出さない")
  void resourceNotFoundIsConvertedTo404() {
    final StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("problem.title.404", Locale.JAPANESE, "リソースが見つかりません");
    final ApiExceptionHandler handler = new ApiExceptionHandler(new ApiProblemDetails(messages));

    final ResponseEntity<Object> response =
        handler.handleResourceNotFoundException(
            new ResourceNotFoundException("row not found: table=t_item"),
            new ServletWebRequest(new MockHttpServletRequest()));

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "HTTP status");
    final ProblemDetail problem =
        assertInstanceOf(ProblemDetail.class, response.getBody(), "body の型");
    assertEquals(404, problem.getStatus(), "problem の status");
    assertEquals("リソースが見つかりません", problem.getTitle(), "ロケールに合わせた title");
    assertNull(problem.getDetail(), "内部のメッセージを detail に出さないこと");
  }
}
