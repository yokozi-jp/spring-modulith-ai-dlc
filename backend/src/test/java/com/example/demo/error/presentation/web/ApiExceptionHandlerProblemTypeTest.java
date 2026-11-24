package com.example.demo.error.presentation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.demo.ordering.domain.model.OrderFailure;
import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.failure.BusinessRuleViolationException;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/** 422 と 409 が about:blank にならず、理由か種類ごとの type と、アプリの messages で翻訳した title で返ることを検証する（ADR-125）。 */
class ApiExceptionHandlerProblemTypeTest {

  /** アプリの messages.properties と messages_ja.properties を読む handler。要求の言語の既定は日本語。 */
  private static ApiExceptionHandler handler() {
    final ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
    messages.setBasename("messages");
    messages.setDefaultEncoding("UTF-8");
    messages.setFallbackToSystemLocale(false);
    return new ApiExceptionHandler(new ApiProblemDetails(messages));
  }

  @ParameterizedTest
  @CsvSource({
    "NOT_DRAFT, /problems/ordering/not-draft, 下書きではない注文です",
    "PRODUCT_NOT_FOUND, /problems/ordering/product-not-found, 商品が見つかりません",
    "PRODUCT_DISCONTINUED, /problems/ordering/product-discontinued, 販売を終了した商品です",
    "NO_LINES, /problems/ordering/no-lines, 注文に明細がありません"
  })
  @DisplayName("注文の業務規則の違反は、理由ごとの type と翻訳した title の 422 になり、メッセージを detail に出さない")
  void businessRuleViolationHasReasonType(
      final OrderFailure reason, final URI type, final String title) {
    final ResponseEntity<Object> response =
        handler()
            .handleBusinessRuleViolationException(
                new BusinessRuleViolationException(reason, "order rejected: orderId=secret-1"),
                new ServletWebRequest(new MockHttpServletRequest()));

    assertNotNull(response, "応答");
    assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.getStatusCode(), "HTTP status");
    final ProblemDetail body = assertInstanceOf(ProblemDetail.class, response.getBody(), "本文");
    assertEquals(type, body.getType(), "type");
    assertEquals(title, body.getTitle(), "title");
    assertNull(body.getDetail(), "detail");
  }

  @ParameterizedTest
  @CsvSource({
    "VERSION, /problems/version-conflict, ほかの操作が先に更新しました",
    "LOCK, /problems/lock-timeout, ほかの操作が更新しています",
    "UNIQUE, /problems/unique-violation, 同じ値がすでに使われています"
  })
  @DisplayName("競合は、種類ごとの type と翻訳した title の 409 になり、メッセージを detail に出さない")
  void conflictHasKindType(final ConflictException.Kind kind, final URI type, final String title) {
    final ResponseEntity<Object> response =
        handler()
            .handleConflictException(
                new ConflictException(kind, "row conflict: table=t_secret"),
                new ServletWebRequest(new MockHttpServletRequest()));

    assertNotNull(response, "応答");
    assertEquals(HttpStatus.CONFLICT, response.getStatusCode(), "HTTP status");
    final ProblemDetail body = assertInstanceOf(ProblemDetail.class, response.getBody(), "本文");
    assertEquals(type, body.getType(), "type");
    assertEquals(title, body.getTitle(), "title");
    assertNull(body.getDetail(), "detail");
  }
}
