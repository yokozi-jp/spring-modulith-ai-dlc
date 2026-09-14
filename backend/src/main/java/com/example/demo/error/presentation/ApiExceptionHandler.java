package com.example.demo.error.presentation;

import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Spring MVC と Spring Security の例外を RFC 9457 Problem Details へ変換する。 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  /** 未認証の API リクエストを 401 Problem Details へ変換する。 */
  @ExceptionHandler(AuthenticationException.class)
  /* package */ ResponseEntity<Object> handleAuthenticationException(
      final AuthenticationException exception, final WebRequest request) {
    return Objects.requireNonNull(
        handleExceptionInternal(
            exception,
            ApiProblemDetails.forStatus(HttpStatus.UNAUTHORIZED),
            problemHeaders(),
            HttpStatus.UNAUTHORIZED,
            request));
  }

  /** 権限または CSRF token が不足する API リクエストを 403 Problem Details へ変換する。 */
  @ExceptionHandler(AccessDeniedException.class)
  /* package */ ResponseEntity<Object> handleAccessDeniedException(
      final AccessDeniedException exception, final WebRequest request) {
    return Objects.requireNonNull(
        handleExceptionInternal(
            exception,
            ApiProblemDetails.forStatus(HttpStatus.FORBIDDEN),
            problemHeaders(),
            HttpStatus.FORBIDDEN,
            request));
  }

  /** MVC 内の未処理例外を、実装詳細を含まない 500 Problem Details へ変換する。 */
  @ExceptionHandler(Exception.class)
  /* package */ ResponseEntity<Object> handleUnexpectedException(
      final Exception exception, final WebRequest request) {
    log.error("Unhandled API exception", exception);
    return Objects.requireNonNull(
        handleExceptionInternal(
            exception,
            ApiProblemDetails.forStatus(HttpStatus.INTERNAL_SERVER_ERROR),
            problemHeaders(),
            HttpStatus.INTERNAL_SERVER_ERROR,
            request));
  }

  @Override
  protected ResponseEntity<Object> createResponseEntity(
      final @Nullable Object body,
      final HttpHeaders headers,
      final HttpStatusCode status,
      final WebRequest request) {
    Optional.ofNullable(body)
        .filter(ProblemDetail.class::isInstance)
        .map(ProblemDetail.class::cast)
        .ifPresent(ApiProblemDetails::normalize);
    return super.createResponseEntity(body, headers, status, request);
  }

  private static HttpHeaders problemHeaders() {
    final HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    return headers;
  }
}
