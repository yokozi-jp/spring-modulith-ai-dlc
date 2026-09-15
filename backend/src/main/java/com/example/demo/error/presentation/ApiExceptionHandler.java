package com.example.demo.error.presentation;

import com.example.demo.LocaleSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Spring MVC と Spring Security の例外を RFC 9457 Problem Details へ変換する。 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  /** Problem Details の共通フィールドを生成する。 */
  private final ApiProblemDetails problemDetails;

  /** Problem Details の共通処理を受け取る。 */
  public ApiExceptionHandler(final ApiProblemDetails problemDetails) {
    super();
    this.problemDetails = problemDetails;
  }

  /** 未認証の API リクエストを 401 Problem Details へ変換する。 */
  @ExceptionHandler(AuthenticationException.class)
  /* package */ ResponseEntity<Object> handleAuthenticationException(
      final AuthenticationException exception, final WebRequest request) {
    return Objects.requireNonNull(
        handleExceptionInternal(
            exception,
            ApiProblemDetails.forStatus(HttpStatus.UNAUTHORIZED),
            new HttpHeaders(),
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
            new HttpHeaders(),
            HttpStatus.FORBIDDEN,
            request));
  }

  /** MVC 内の未処理例外を、実装詳細を含まない 500 Problem Details へ変換する。 */
  @ExceptionHandler(Exception.class)
  /* package */ ResponseEntity<Object> handleUnexpectedException(
      final Exception exception, final WebRequest request) {
    log.atError()
        .addKeyValue("exception.type", exception.getClass().getName())
        .addKeyValue("exception.stacktrace", ExceptionLogSanitizer.stackTrace(exception))
        .log("Unhandled API exception");
    return Objects.requireNonNull(
        handleExceptionInternal(
            exception,
            ApiProblemDetails.forStatus(HttpStatus.INTERNAL_SERVER_ERROR),
            new HttpHeaders(),
            HttpStatus.INTERNAL_SERVER_ERROR,
            request));
  }

  @Override
  protected ResponseEntity<Object> createResponseEntity(
      final @Nullable Object body,
      final HttpHeaders headers,
      final HttpStatusCode status,
      final WebRequest request) {
    final Locale locale = resolveLocale(request);
    Optional.ofNullable(body)
        .filter(ProblemDetail.class::isInstance)
        .map(ProblemDetail.class::cast)
        .ifPresent(problem -> problemDetails.normalize(problem, status, locale));
    return super.createResponseEntity(
        body, ApiProblemDetails.responseHeaders(headers, locale), status, request);
  }

  private static Locale resolveLocale(final WebRequest request) {
    if (request instanceof ServletWebRequest servletWebRequest) {
      final HttpServletRequest servletRequest = servletWebRequest.getRequest();
      return LocaleSupport.resolve(servletRequest);
    }
    return LocaleSupport.defaultLocale();
  }
}
