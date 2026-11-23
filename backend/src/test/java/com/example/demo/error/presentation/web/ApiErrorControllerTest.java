package com.example.demo.error.presentation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.SourceDSL.integers;

import jakarta.servlet.RequestDispatcher;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** {@code /error} が転送元の status を API 契約どおりに返すことを検証する。 */
class ApiErrorControllerTest {

  /** MessageSource に key のない状態の検証対象。 */
  private final ApiErrorController controller =
      new ApiErrorController(new ApiProblemDetails(new StaticMessageSource()));

  @Test
  @DisplayName("400 から 599 の status は HttpStatus に定義がなくてもそのまま返し、それ以外は 500 にする")
  void errorKeepsOnlyClientAndServerErrorStatus() {
    qt().withExamples(2_000)
        .forAll(integers().between(-1_000, 2_000))
        .checkAssert(
            code -> {
              final int expected = code >= 400 && code <= 599 ? code : 500;
              final MockHttpServletRequest request = new MockHttpServletRequest();
              request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, code);

              final ResponseEntity<ProblemDetail> response = controller.error(request);

              assertEquals(expected, response.getStatusCode().value(), "応答の status: 入力 " + code);
              assertEquals(expected, body(response).getStatus(), "body の status: 入力 " + code);
            });
  }

  @Test
  @DisplayName("status の属性がなければ 500 にする")
  void errorWithoutStatusAttributeReturnsInternalServerError() {
    assertInternalServerError(new MockHttpServletRequest());
  }

  @Test
  @DisplayName("status の属性が Integer でなければ 500 にする")
  void errorWithNonIntegerStatusAttributeReturnsInternalServerError() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, "499");

    assertInternalServerError(request);
  }

  private void assertInternalServerError(final MockHttpServletRequest request) {
    final ResponseEntity<ProblemDetail> response = controller.error(request);

    assertEquals(500, response.getStatusCode().value(), "応答の status");
    assertEquals(500, body(response).getStatus(), "body の status");
  }

  private static ProblemDetail body(final ResponseEntity<ProblemDetail> response) {
    return Objects.requireNonNull(response.getBody(), "Problem Details の本文");
  }
}
