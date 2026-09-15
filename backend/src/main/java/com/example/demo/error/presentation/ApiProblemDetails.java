package com.example.demo.error.presentation;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/** RFC 9457 Problem Details の共通フィールドを生成する。 */
@Component
final class ApiProblemDetails {

  /** RFC 9457 が一般的な HTTP エラーに定義する problem type。 */
  private static final URI ABOUT_BLANK = URI.create("about:blank");

  /** ロケール別の API エラーメッセージを解決する。 */
  private final MessageSource messageSource;

  /** アプリケーションの MessageSource を受け取る。 */
  /* package */ ApiProblemDetails(final MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  /** {@link ApiExceptionHandler} が応答生成時に正規化する Problem Details を生成する。 */
  /* package */ static ProblemDetail forStatus(final HttpStatus status) {
    return ProblemDetail.forStatus(status);
  }

  /** advice を通らない {@code /error} 用に、正規化済み Problem Details を生成する。 */
  /* package */ ProblemDetail localizedForStatus(final HttpStatus status, final Locale locale) {
    final ProblemDetail problem = forStatus(status);
    normalize(problem, status, locale);
    return problem;
  }

  /** 標準 type と、人が読む title を API の契約へ正規化する。 */
  /* package */ void normalize(
      final ProblemDetail problem, final HttpStatusCode status, final Locale locale) {
    final URI type = Objects.requireNonNullElse(problem.getType(), ABOUT_BLANK);
    problem.setType(type);
    if (ABOUT_BLANK.equals(type)) {
      problem.setDetail(null);
    }
    final String defaultTitle =
        status instanceof HttpStatus httpStatus ? httpStatus.getReasonPhrase() : status.toString();
    problem.setTitle(
        messageSource.getMessage("problem.title." + status.value(), null, defaultTitle, locale));
  }

  /** 元の Vary を保ち、Problem Details の media type と選択言語を応答ヘッダへ設定する。 */
  /* package */ static HttpHeaders responseHeaders(
      final HttpHeaders original, final Locale locale) {
    final HttpHeaders headers = new HttpHeaders();
    headers.putAll(original);
    headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    headers.setContentLanguage(locale);
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
