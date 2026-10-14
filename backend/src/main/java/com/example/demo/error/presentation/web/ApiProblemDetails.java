package com.example.demo.error.presentation.web;

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
// problem type ごとの生成と検証の誤りの組み立てを 1 つの MessageSource に集めるため、メソッドの数の上限を外す。
// type と title の対応は businessProblem の 1 か所に閉じている。型ごとに分けると MessageSource の受け渡しが増える。
@SuppressWarnings("PMD.TooManyMethods")
@Component
final class ApiProblemDetails {

  /** RFC 9457 が一般的な HTTP エラーに定義する problem type。 */
  private static final URI ABOUT_BLANK = URI.create("about:blank");

  /** 業務固有の problem type の接頭辞。パスを全部書いた相対 URI にする（ADR-058、RFC 9457 §3.1.1）。 */
  private static final String PROBLEM_TYPE_PREFIX = "/problems/";

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
    final URI type = Objects.requireNonNullElse(problem.getType(), ABOUT_BLANK);
    problem.setType(type);
    if (ABOUT_BLANK.equals(type)) {
      problem.setDetail(null);
      final HttpStatus titleStatus =
          status instanceof HttpStatus httpStatus
              ? httpStatus
              : status.is4xxClientError()
                  ? HttpStatus.BAD_REQUEST
                  : HttpStatus.INTERNAL_SERVER_ERROR;
      problem.setTitle(
          messageSource.getMessage(
              "problem.title." + titleStatus.value(), null, titleStatus.getReasonPhrase(), locale));
    }
  }

  /**
   * 入力検証エラーの 400 を作る。title は problem.title.validation-error を解決し、detail は付けない。
   *
   * <p>normalize は about:blank 以外の title と detail を変えないため、ここで title を決める。
   */
  /* package */ ProblemDetail validationProblem(
      final List<ValidationError> errors, final Locale locale) {
    final ProblemDetail problem =
        businessProblem(
            HttpStatus.BAD_REQUEST, "validation-error", "Invalid request content", locale);
    problem.setProperty(ERRORS, errors);
    return problem;
  }

  /** CSRF トークンの検証失敗の 403 を作る。title は problem.title.csrf-token-invalid を解決し、detail は付けない。 */
  /* package */ ProblemDetail csrfTokenProblem(final Locale locale) {
    return businessProblem(
        HttpStatus.FORBIDDEN, "csrf-token-invalid", "Invalid or missing CSRF token", locale);
  }

  /**
   * 業務固有の problem type の Problem Details を作る。
   *
   * <p>type は {@code /problems/<kebab-case>}、title は {@code problem.title.<kebab-case>} を解決する。
   * normalize は about:blank 以外の title と detail を変えないため、ここで title を決め、detail は付けない。
   */
  private ProblemDetail businessProblem(
      final HttpStatus status,
      final String kebabName,
      final String defaultTitle,
      final Locale locale) {
    final ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(URI.create(PROBLEM_TYPE_PREFIX + kebabName));
    problem.setTitle(
        messageSource.getMessage("problem.title." + kebabName, null, defaultTitle, locale));
    return problem;
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
            .flatMap(
                result -> {
                  final String origin = JsonPointers.origin(result);
                  if (result instanceof ParameterErrors parameterErrors) {
                    return errorsOf(origin, parameterErrors, locale);
                  }
                  return result.getResolvableErrors().stream()
                      .map(
                          error ->
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
        .map(
            violation ->
                new ValidationError(
                    JsonPointers.fromPath(violation.getPropertyPath()), violation.getMessage()))
        .sorted(ERROR_ORDER)
        .toList();
  }

  /** 戻り値の違反を含むか。 */
  /* package */ static boolean hasReturnValueViolation(final ConstraintViolationException ex) {
    return violations(ex).stream()
        .anyMatch(
            violation ->
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
            .map(
                error ->
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
