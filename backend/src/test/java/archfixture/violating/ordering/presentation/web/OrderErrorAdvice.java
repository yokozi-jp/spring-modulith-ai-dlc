package archfixture.violating.ordering.presentation.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 違反：httpErrorsAreMappedOnlyInErrorPresentationWeb（機能モジュールが例外を Problem Details へ変える）。 */
@RestControllerAdvice
class OrderErrorAdvice {

  /** 状態の誤りを、モジュールで決めた 422 の Problem Details にする。 */
  @ExceptionHandler(IllegalStateException.class)
  @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
  /* package */ ProblemDetail handle(final IllegalStateException exception) {
    return ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
  }
}
