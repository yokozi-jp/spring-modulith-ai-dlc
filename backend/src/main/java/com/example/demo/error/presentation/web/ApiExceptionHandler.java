package com.example.demo.error.presentation.web;

import com.example.demo.LocaleSupport;
import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.failure.BusinessRuleViolationException;
import com.example.demo.shared.failure.NotFoundException;
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

/**
 * Spring MVC と Spring Security の例外と、業務上の失敗（shared.failure と shared.concurrency）を RFC 9457 Problem
 * Details へ変換する。
 */
// 基底クラスの override と例外ごとの @ExceptionHandler は、1 つの advice に置く必要がある。
@SuppressWarnings("PMD.TooManyMethods")
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

  /** メソッド検証の違反を 400 にする。戻り値の違反は実装の不備なので 500 にする。 */
  @ExceptionHandler(ConstraintViolationException.class)
  /* package */ @Nullable ResponseEntity<Object> handleConstraintViolationException(
      final ConstraintViolationException exception, final WebRequest request) {
    if (ApiProblemDetails.hasReturnValueViolation(exception)) {
      return problem(exception, HttpStatus.INTERNAL_SERVER_ERROR, request);
    }
    final ProblemDetail body =
        problemDetails.validationProblem(
            ApiProblemDetails.validationErrors(exception), resolveLocale(request));
    return handleExceptionInternal(
        exception, body, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
  }

  /** 楽観的ロックの競合を 409 Problem Details へ変換する。例外の内容は応答に入れない。 */
  @ExceptionHandler(ConflictException.class)
  /* package */ @Nullable ResponseEntity<Object> handleConflictException(
      final ConflictException exception, final WebRequest request) {
    return problem(exception, HttpStatus.CONFLICT, request);
  }

  /** 見つからない集約や行を 404 Problem Details へ変換する。例外の内容は応答に入れない。 */
  @ExceptionHandler(NotFoundException.class)
  /* package */ @Nullable ResponseEntity<Object> handleNotFoundException(
      final NotFoundException exception, final WebRequest request) {
    return problem(exception, HttpStatus.NOT_FOUND, request);
  }

  /** 業務規則の違反を 422 Problem Details へ変換する。例外の内容は応答に入れない。 */
  @ExceptionHandler(BusinessRuleViolationException.class)
  /* package */ @Nullable ResponseEntity<Object> handleBusinessRuleViolationException(
      final BusinessRuleViolationException exception, final WebRequest request) {
    return problem(exception, HttpStatus.UNPROCESSABLE_CONTENT, request);
  }

  /** MVC 内の未処理例外を、実装詳細を含まない 500 Problem Details へ変換する。 */
  @ExceptionHandler(Exception.class)
  /* package */ @Nullable ResponseEntity<Object> handleUnexpectedException(
      final Exception exception, final WebRequest request) {
    return problem(exception, HttpStatus.INTERNAL_SERVER_ERROR, request);
  }

  /** 本文の検証エラーを、errors 拡張を持つ入力検証エラーの 400 にする。 */
  @Override
  protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
      final MethodArgumentNotValidException ex,
      final HttpHeaders headers,
      final HttpStatusCode status,
      final WebRequest request) {
    final Locale locale = resolveLocale(request);
    // framework の detail を公開しないよう、ex.getBody() を使わずに作る。
    final ProblemDetail body =
        problemDetails.validationProblem(
            problemDetails.validationErrors(ex.getBindingResult(), locale), locale);
    return handleExceptionInternal(ex, body, headers, status, request);
  }

  /** 引数の検証エラーを、errors 拡張を持つ入力検証エラーの 400 にする。 */
  @Override
  protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
      final HandlerMethodValidationException ex,
      final HttpHeaders headers,
      final HttpStatusCode status,
      final WebRequest request) {
    if (ex.isForReturnValue()) {
      // 戻り値の違反は基底クラスどおり 500 の about:blank にする。
      return super.handleHandlerMethodValidationException(ex, headers, status, request);
    }
    final Locale locale = resolveLocale(request);
    final ProblemDetail body =
        problemDetails.validationProblem(problemDetails.validationErrors(ex, locale), locale);
    return handleExceptionInternal(ex, body, headers, status, request);
  }

  /** 全ての例外が通る。ログを 1 か所で出し、コミット済みの応答では本文を書かない。 */
  @Override
  protected @Nullable ResponseEntity<Object> handleExceptionInternal(
      final Exception ex,
      final @Nullable Object body,
      final HttpHeaders headers,
      final HttpStatusCode statusCode,
      final WebRequest request) {
    if (isCommitted(request)) {
      // 切断かどうかは例外の型とメッセージからの推測なので、本文を書けないコミット後だけ使う。
      if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
        log.atDebug().log("API client disconnected");
      } else {
        logFailure(ex, statusCode);
      }
      // 基底クラスの WARN を重ねない。
      return null;
    }
    // コミット前は切断らしく見えても Problem Details を返す。
    // 外部との通信の "connection reset" を空の 200 にしないためである。
    // 本当に切断していれば、書き込みの失敗を Spring が切断として DEBUG で捨てる。
    // ponytail: コミット前にブラウザが切断すると ERROR が 1 件出る。目立つようになったら見直す。
    logFailure(ex, statusCode);
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
        body, ApiProblemDetails.responseHeaders(headers, status, locale), status, request);
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
    } else if (ex instanceof ConflictException) {
      // クライアントが読み直して再送できる想定内の 4xx なので、ERROR にせず INFO で残す。
      // WARN 以上は起動時と Collector の障害用のロググループにも出るため使わない。
      log.atInfo().setCause(ex).addKeyValue(STATUS_CODE, status.value()).log("API conflict");
    } else if (ex instanceof NotFoundException || ex instanceof BusinessRuleViolationException) {
      // 業務上の想定内の 4xx なので INFO で残す。対象を調べられるよう例外を付ける。
      log.atInfo()
          .setCause(ex)
          .addKeyValue(STATUS_CODE, status.value())
          .log("API business failure");
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
