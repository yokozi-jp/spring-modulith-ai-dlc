package com.example.demo.error.presentation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.demo.shared.concurrency.ConflictException;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/** 未処理例外を汎用の 500 応答へ、楽観的ロックの競合を 409 応答へ変換し、調査用の例外の詳細をログへ残すことを検証する。 */
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
  @DisplayName("ConflictException は 409 を返し、ERROR ではなく INFO で例外の型を残す")
  void conflictExceptionIsLoggedAtInfo(final CapturedOutput output) {
    final ApiExceptionHandler handler =
        new ApiExceptionHandler(new ApiProblemDetails(new StaticMessageSource()));

    final ResponseEntity<Object> response =
        handler.handleConflictException(
            new ConflictException("order was updated by another request"),
            new ServletWebRequest(new MockHttpServletRequest()));

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode(), "HTTP status");
    final String log = output.getAll();
    assertTrue(log.contains("Optimistic lock conflict"), () -> "event 名が残ること: " + log);
    assertTrue(log.contains("INFO"), () -> "INFO で残ること: " + log);
    assertTrue(log.contains(ConflictException.class.getName()), () -> "例外の型が残ること: " + log);
    assertFalse(log.contains("ERROR"), () -> "ERROR で残さないこと: " + log);
  }
}
