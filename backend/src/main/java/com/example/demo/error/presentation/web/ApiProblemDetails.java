package com.example.demo.error.presentation.web;

import com.example.demo.shared.failure.FailureReason;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** RFC 9457 Problem Details の共通フィールドを生成する。 */
// Problem Details と検証の誤りの組み立てを 1 つの MessageSource に集めるため、メソッドの数の上限を外す。
// type と title のキーは ProblemTypes が作る。
@SuppressWarnings("PMD.TooManyMethods")
@Component
final class ApiProblemDetails {

  /** 入力検証エラー（/problems/validation-error）の拡張メンバーで、入力検証の誤りを入れる（ADR-058）。 */
  /* package */ static final String ERRORS = "errors";

  /**
   * 401 の WWW-Authenticate に入れる challenge（RFC 9110 §15.5.2）。
   *
   * <p>Cookie セッションに合う登録済み scheme がないため、独自の Session scheme にする（ADR-059）。
   */
  /* package */ static final String WWW_AUTHENTICATE_CHALLENGE = "Session realm=\"demo\"";

  /** クライアントとテストが安定するよう、errors を pointer、detail の順に並べる。 */
  private static final Comparator<ValidationError> ERROR_ORDER =
      Comparator.comparing(ValidationError::pointer).thenComparing(ValidationError::detail);

  /** ロケール別の API エラーメッセージを解決する。 */
  private final MessageSource messageSource;

  /** アプリケーションの MessageSource を受け取る。 */
  /* package */ ApiProblemDetails(final MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  /** advice を通らない {@code /error} 用に、正規化済み Problem Details を生成する。 */
  /* package */ ProblemDetail localizedForStatus(final HttpStatusCode status, final Locale locale) {
    final ProblemDetail problem = ProblemDetail.forStatus(status);
    normalize(problem, status, locale);
    return problem;
  }

  /**
   * 標準 type を補い、about:blank のときだけ title を翻訳して detail を消す。業務 type は変えない。
   *
   * <p>HttpStatus に定義のない status の title は、4xx なら 400、それ以外なら 500 の文言にする。 status は変えない。
   */
  /* package */ void normalize(
      final ProblemDetail problem, final HttpStatusCode status, final Locale locale) {
    final URI type = Objects.requireNonNullElse(problem.getType(), ProblemTypes.ABOUT_BLANK);
    problem.setType(type);
    if (ProblemTypes.ABOUT_BLANK.equals(type)) {
      problem.setDetail(null);
      final HttpStatus titleStatus =
          status instanceof HttpStatus httpStatus
              ? httpStatus
              : status.is4xxClientError()
                  ? HttpStatus.BAD_REQUEST
                  : HttpStatus.INTERNAL_SERVER_ERROR;
      problem.setTitle(statusTitle(titleStatus, locale));
    }
  }

  /** 入力検証エラーの 400 を作る。title は {@link CommonProblem#VALIDATION_ERROR} の title を解決し、detail は付けない。 */
  /* package */ ProblemDetail validationProblem(
      final List<ValidationError> errors, final Locale locale) {
    final ProblemDetail problem = problem(CommonProblem.VALIDATION_ERROR, locale);
    problem.setProperty(ERRORS, errors);
    return problem;
  }

  /** モジュールによらない problem を作る。detail は付けない（ADR-125）。 */
  /* package */ ProblemDetail problem(final CommonProblem common, final Locale locale) {
    return typedProblem(common.status(), ProblemTypes.path(common), locale);
  }

  /** 業務規則の違反の 422 を作る。type と title は理由から決まり、detail は付けない（ADR-125）。 */
  /* package */ ProblemDetail businessRuleViolationProblem(
      final FailureReason reason, final Locale locale) {
    return typedProblem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.path(reason), locale);
  }

  /**
   * パスの type と、パスの title のキーを解決した title を持つ Problem Details を作る。キーがなければ status の title にする。
   *
   * <p>normalize は about:blank 以外の title と detail を変えないため、ここで title を決め、detail は付けない。
   */
  private ProblemDetail typedProblem(
      final HttpStatus status, final String path, final Locale locale) {
    final ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(ProblemTypes.type(path));
    problem.setTitle(
        messageSource.getMessage(
            ProblemTypes.titleKey(path), null, statusTitle(status, locale), locale));
    return problem;
  }

  /** status の title（{@code problem.title.<status>}）を解決する。キーがなければ reason phrase にする。 */
  private String statusTitle(final HttpStatus status, final Locale locale) {
    final String reasonPhrase = status.getReasonPhrase();
    return Objects.requireNonNullElse(
        messageSource.getMessage(
            ProblemTypes.TITLE_PREFIX + status.value(), null, reasonPhrase, locale),
        reasonPhrase);
  }

  /** {@code @Valid} の本文の誤りを、本文の root を起点にした pointer で返す。 */
  /* package */ List<ValidationError> validationErrors(
      final BindingResult result, final Locale locale) {
    return errorsOf("", result, locale).sorted(ERROR_ORDER).toList();
  }

  /**
   * Spring MVC のメソッド検証の誤りを返す。
   *
   * <p>{@code @RequestBody} は本文の root、それ以外は {@code /<パラメータ名>} を起点にする。
   */
  /* package */ List<ValidationError> validationErrors(
      final HandlerMethodValidationException ex, final Locale locale) {
    final Stream<ValidationError> parameters =
        ex.getParameterValidationResults().stream()
            .flatMap(result -> {
              final String origin = JsonPointers.origin(result);
              if (result instanceof ParameterErrors parameterErrors) {
                return errorsOf(origin, parameterErrors, locale);
              }
              return result.getResolvableErrors().stream()
                  .map(error ->
                      new ValidationError(origin, messageSource.getMessage(error, locale)));
            });
    final Stream<ValidationError> crossParameters =
        ex.getCrossParameterValidationResults().stream()
            .map(error -> new ValidationError("", messageSource.getMessage(error, locale)));
    return Stream.concat(parameters, crossParameters).sorted(ERROR_ORDER).toList();
  }

  /** Bean Validation の違反を返す。detail は Validator が補間した文言を使う。 */
  /* package */ static List<ValidationError> validationErrors(
      final ConstraintViolationException ex) {
    return violations(ex).stream()
        .map(violation ->
            new ValidationError(
                JsonPointers.fromPath(violation.getPropertyPath()), violation.getMessage()))
        .sorted(ERROR_ORDER)
        .toList();
  }

  /** 戻り値の違反を含むか。 */
  /* package */ static boolean hasReturnValueViolation(final ConstraintViolationException ex) {
    return violations(ex).stream()
        .anyMatch(violation ->
            StreamSupport.stream(violation.getPropertyPath().spliterator(), false)
                .anyMatch(node -> node.getKind() == ElementKind.RETURN_VALUE));
  }

  /** 入力検証の誤り 1 件。pointer は RFC 6901 の JSON Pointer、detail は入力値を含まない説明。 */
  /* package */ record ValidationError(String pointer, String detail) {}

  /** 項目と object 全体の誤りを、起点の下の pointer にする。拒否された値は使わない。 */
  private Stream<ValidationError> errorsOf(
      final String origin, final Errors source, final Locale locale) {
    return Stream.concat(
        source.getFieldErrors().stream()
            .map(error ->
                new ValidationError(
                    JsonPointers.fromPropertyPath(origin, error.getField()),
                    messageSource.getMessage(error, locale))),
        source.getGlobalErrors().stream()
            .map(error -> new ValidationError(origin, messageSource.getMessage(error, locale))));
  }

  private static Set<ConstraintViolation<?>> violations(final ConstraintViolationException ex) {
    final @Nullable Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
    return violations == null ? Set.of() : violations;
  }

  /**
   * 元の Vary を保ち、Problem Details の media type と選択言語を応答ヘッダへ設定する。
   *
   * <p>401 には WWW_AUTHENTICATE_CHALLENGE を付ける。
   */
  /* package */ static HttpHeaders responseHeaders(
      final HttpHeaders original, final HttpStatusCode status, final Locale locale) {
    final HttpHeaders headers = new HttpHeaders();
    headers.putAll(original);
    headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    headers.setContentLanguage(locale);
    if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
      headers.set(HttpHeaders.WWW_AUTHENTICATE, WWW_AUTHENTICATE_CHALLENGE);
    }
    final List<String> vary = new ArrayList<>(headers.getVary());
    if (vary.stream()
        .noneMatch(
            value -> "*".equals(value) || HttpHeaders.ACCEPT_LANGUAGE.equalsIgnoreCase(value))) {
      vary.add(HttpHeaders.ACCEPT_LANGUAGE);
      headers.setVary(vary);
    }
    return headers;
  }
}
