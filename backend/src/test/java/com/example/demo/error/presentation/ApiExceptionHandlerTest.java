package com.example.demo.error.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** 未処理例外ログが PII を除外し、診断用 stack frame を保持することを検証する。 */
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

  @Test
  @DisplayName("未処理例外ログはメッセージを除外し stack frame と cause の型を残す")
  void unexpectedExceptionLogKeepsSanitizedStackTrace(final CapturedOutput output) {
    final StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("problem.title.500", Locale.JAPANESE, "サーバー内部エラー");
    final ApiExceptionHandler handler = new ApiExceptionHandler(new ApiProblemDetails(messages));
    final MockHttpServletRequest request = new MockHttpServletRequest();
    final String sensitiveMessage = "person@example.com token=secret";
    final String sensitiveCauseMessage = "account=customer@example.com";
    final IllegalArgumentException cause = new IllegalArgumentException(sensitiveCauseMessage);
    cause.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement(
              "com.example.demo.OrderRepository", "save", "OrderRepository.java", 57)
        });
    final IllegalStateException exception = new IllegalStateException(sensitiveMessage, cause);
    exception.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement("com.example.demo.OrderService", "create", "OrderService.java", 84)
        });

    final ResponseEntity<Object> response =
        handler.handleUnexpectedException(exception, new ServletWebRequest(request));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(), "HTTP status");
    assertTrue(output.getAll().contains("OrderService.java:84"), "ログに例外発生行が含まれること");
    assertTrue(
        output.getAll().contains(IllegalArgumentException.class.getName()), "ログに cause の型が含まれること");
    assertFalse(output.getAll().contains(sensitiveMessage), "ログに例外メッセージが含まれないこと");
    assertFalse(output.getAll().contains(sensitiveCauseMessage), "ログに cause のメッセージが含まれないこと");
  }
}
