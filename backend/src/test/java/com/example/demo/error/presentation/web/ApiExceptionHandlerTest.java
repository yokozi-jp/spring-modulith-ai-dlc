package com.example.demo.error.presentation.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

/** 例外を Problem Details へ変換し、ログを 1 か所で出し、切断とコミット済みの応答を扱うことを検証する。 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyStaticImports"})
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

  /** 違反を作る Bean Validation の Validator。 */
  private static final Validator VALIDATOR;

  static {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      VALIDATOR = factory.getValidator();
    }
  }

  private static ApiExceptionHandler handler() {
    final StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("problem.title.500", Locale.JAPANESE, "サーバー内部エラー");
    messages.addMessage("problem.title.validation-error", Locale.JAPANESE, "入力内容に誤りがあります");
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

  @ParameterizedTest
  @MethodSource("constraintViolations")
  @DisplayName("ConstraintViolationException は入力検証エラーの 400 と errors の pointer にする")
  void constraintViolationBecomesValidationProblem(
      final Set<ConstraintViolation<?>> violations, final String pointer) {
    final ResponseEntity<Object> response =
        handler()
            .handleConstraintViolationException(
                new ConstraintViolationException(violations),
                new ServletWebRequest(new MockHttpServletRequest()));

    assertNotNull(response, "応答");
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "HTTP status");
    final ProblemDetail body = assertInstanceOf(ProblemDetail.class, response.getBody(), "本文");
    assertEquals(URI.create("/problems/validation-error"), body.getType(), "type");
    assertEquals("入力内容に誤りがあります", body.getTitle(), "title");
    assertNull(body.getDetail(), "detail");
    final Map<String, Object> properties = body.getProperties();
    assertNotNull(properties, "拡張メンバー");
    final List<?> errors = assertInstanceOf(List.class, properties.get("errors"), "errors");
    final ApiProblemDetails.ValidationError error =
        assertInstanceOf(ApiProblemDetails.ValidationError.class, errors.getFirst(), "誤り");
    assertEquals(1, errors.size(), "誤りの件数");
    assertEquals(pointer, error.pointer(), "pointer");
  }

  @Test
  @DisplayName("戻り値の制約違反を含む ConstraintViolationException は 500 の about:blank にする")
  void returnValueViolationIsServerError() throws NoSuchMethodException {
    final Set<ConstraintViolation<Finder>> violations =
        VALIDATOR
            .forExecutables()
            .validateReturnValue(
                new Finder(), Finder.class.getDeclaredMethod("find", int.class), 0);

    final ResponseEntity<Object> response =
        handler()
            .handleConstraintViolationException(
                new ConstraintViolationException(violations),
                new ServletWebRequest(new MockHttpServletRequest()));

    assertNotNull(response, "応答");
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(), "HTTP status");
    final ProblemDetail body = assertInstanceOf(ProblemDetail.class, response.getBody(), "本文");
    assertEquals(URI.create("about:blank"), body.getType(), "type");
  }

  private static Stream<Arguments> constraintViolations() throws NoSuchMethodException {
    return Stream.of(
        Arguments.of(VALIDATOR.validate(new Item("too-long")), "/code"),
        Arguments.of(VALIDATOR.validate(new Order(List.of(new Item("too-long")))), "/items/0/code"),
        Arguments.of(
            VALIDATOR
                .forExecutables()
                .validateParameters(
                    new Finder(),
                    Finder.class.getDeclaredMethod("find", int.class),
                    new Object[] {0}),
            "/limit"));
  }

  /** 制約を持つ要素。 */
  private record Item(@Size(max = 3) String code) {}

  /** 要素の一覧を入れ子で検証する。 */
  private record Order(@Valid List<Item> items) {}

  /** 引数と戻り値の制約を持つメソッド。 */
  private static final class Finder {
    // Validator が反射で検証するだけで、呼び出さない。
    @SuppressWarnings("UnusedMethod")
    /* package */ @Min(1) int find(@Min(1) final int limit) {
      return limit;
    }
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
