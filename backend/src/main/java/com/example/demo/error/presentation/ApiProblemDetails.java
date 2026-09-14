package com.example.demo.error.presentation;

import java.net.URI;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** RFC 9457 Problem Details の共通フィールドを生成する。 */
final class ApiProblemDetails {

  /** RFC 9457 が一般的な HTTP エラーに定義する problem type。 */
  private static final URI ABOUT_BLANK = URI.create("about:blank");

  private ApiProblemDetails() {}

  /** 実装詳細を含まない Problem Details を生成する。 */
  /* package */ static ProblemDetail forStatus(final HttpStatus status) {
    final ProblemDetail problem = ProblemDetail.forStatus(status);
    normalize(problem);
    problem.setTitle(status.getReasonPhrase());
    return problem;
  }

  /** 省略可能な標準 type を JSON 契約上の明示値へ正規化する。 */
  /* package */ static void normalize(final ProblemDetail problem) {
    problem.setType(Objects.requireNonNullElse(problem.getType(), ABOUT_BLANK));
  }
}
