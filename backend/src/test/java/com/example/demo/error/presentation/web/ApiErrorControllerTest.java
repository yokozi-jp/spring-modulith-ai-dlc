package com.example.demo.error.presentation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.RequestDispatcher;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** {@code /error} が転送元の status を API 契約どおりに返すことを検証する。 */
class ApiErrorControllerTest {

  /** MessageSource に key のない状態の検証対象。 */
  private final ApiErrorController controller =
      new ApiErrorController(new ApiProblemDetails(new StaticMessageSource()));

  @ParameterizedTest
  @ValueSource(ints = {499, 400, 599})
  @DisplayName("HttpStatus に定義のない status も含め、4xx と 5xx はそのまま返す")
  void errorKeepsClientAndServerErrorStatus(final int code) {
    assertStatus(code, code);
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 302, 600, 1000, -1})
  @DisplayName("4xx と 5xx 以外の status は 500 にする")
  void errorFallsBackToInternalServerError(final int code) {
    assertStatus(code, 500);
  }

  @Test
  @DisplayName("status の属性がなければ 500 にする")
  void errorWithoutStatusAttributeReturnsInternalServerError() {
    final ResponseEntity<ProblemDetail> response = controller.error(new MockHttpServletRequest());

    assertEquals(500, response.getStatusCode().value(), "応答の status");
    assertEquals(500, body(response).getStatus(), "body の status");
  }

  private void assertStatus(final int code, final int expected) {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, code);

    final ResponseEntity<ProblemDetail> response = controller.error(request);

    assertEquals(expected, response.getStatusCode().value(), "応答の status: 入力 " + code);
    assertEquals(expected, body(response).getStatus(), "body の status: 入力 " + code);
  }

  private static ProblemDetail body(final ResponseEntity<ProblemDetail> response) {
    return Objects.requireNonNull(response.getBody(), "Problem Details の本文");
  }
}
