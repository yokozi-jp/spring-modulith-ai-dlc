package com.example.demo.error.presentation.web;

import com.example.demo.LocaleSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.DisconnectedClientHelper;

/** Spring MVC と Spring Security の例外を RFC 9457 Problem Details へ変換する。 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  /** ログの属性名（OpenTelemetry の semantic conventions）。 */
  private static final String STATUS_CODE = "http.response.status_code";

  /** Problem Details の共通フィールドを生成する。 */
  private final ApiProblemDetails problemDetails;

  /** Problem Details の共通処理を受け取る。 */
  public ApiExceptionHandler(final ApiProblemDetails problemDetails) {
    super();
    this.problemDetails = problemDetails;
  }

  /** 未認証の API リクエストを 401 Problem Details へ変換する。 */
  @ExceptionHandler(AuthenticationException.class)
  /* package */ @Nullable ResponseEntity<Object> handleAuthenticationException(
      final AuthenticationException exception, final WebRequest request) {
    return problem(exception, HttpStatus.UNAUTHORIZED, request);
  }

  /** 権限または CSRF token が不足する API リクエストを 403 Problem Details へ変換する。 */
  @ExceptionHandler(AccessDeniedException.class)
  /* package */ @Nullable ResponseEntity<Object> handleAccessDeniedException(
      final AccessDeniedException exception, final WebRequest request) {
    return problem(exception, HttpStatus.FORBIDDEN, request);
  }

  /** MVC 内の未処理例外を、実装詳細を含まない 500 Problem Details へ変換する。 */
  @ExceptionHandler(Exception.class)
  /* package */ @Nullable ResponseEntity<Object> handleUnexpectedException(
      final Exception exception, final WebRequest request) {
    return problem(exception, HttpStatus.INTERNAL_SERVER_ERROR, request);
  }

  /** 全ての例外が通る。ログを 1 か所で出し、切断とコミット済みの応答では本文を書かない。 */
  @Override
  protected @Nullable ResponseEntity<Object> handleExceptionInternal(
      final Exception ex,
      final @Nullable Object body,
      final HttpHeaders headers,
      final HttpStatusCode statusCode,
      final WebRequest request) {
    if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
      log.atDebug().log("API client disconnected");
      return null;
    }
    logFailure(ex, statusCode);
    if (isCommitted(request)) {
      // 基底クラスの WARN を重ねない。
      return null;
    }
    return super.handleExceptionInternal(ex, body, headers, statusCode, request);
  }

  @Override
  protected ResponseEntity<Object> createResponseEntity(
      final @Nullable Object body,
      final HttpHeaders headers,
      final HttpStatusCode status,
      final WebRequest request) {
    final Locale locale = resolveLocale(request);
    if (body instanceof ProblemDetail problem) {
      problemDetails.normalize(problem, status, locale);
    }
    return super.createResponseEntity(
        body, ApiProblemDetails.responseHeaders(headers, locale), status, request);
  }

  private @Nullable ResponseEntity<Object> problem(
      final Exception exception, final HttpStatus status, final WebRequest request) {
    return handleExceptionInternal(
        exception, ProblemDetail.forStatus(status), new HttpHeaders(), status, request);
  }

  private static void logFailure(final Exception ex, final HttpStatusCode status) {
    if (status.is5xxServerError()) {
      log.atError()
          .setCause(ex)
          .addKeyValue(STATUS_CODE, status.value())
          .log("Unhandled API exception");
    } else if (ex instanceof MethodArgumentNotValidException
        || ex instanceof HandlerMethodValidationException
        || ex instanceof ConstraintViolationException) {
      log.atWarn().addKeyValue(STATUS_CODE, status.value()).log("API validation failed");
    } else {
      log.atInfo().addKeyValue(STATUS_CODE, status.value()).log("API client error");
    }
  }

  private static boolean isCommitted(final WebRequest request) {
    if (request instanceof ServletWebRequest servletWebRequest) {
      final @Nullable HttpServletResponse response = servletWebRequest.getResponse();
      return response != null && response.isCommitted();
    }
    return false;
  }

  private static Locale resolveLocale(final WebRequest request) {
    if (request instanceof ServletWebRequest servletWebRequest) {
      final HttpServletRequest servletRequest = servletWebRequest.getRequest();
      return LocaleSupport.resolve(servletRequest);
    }
    return LocaleSupport.defaultLocale();
  }
}
