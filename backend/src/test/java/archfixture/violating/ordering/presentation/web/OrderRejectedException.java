package archfixture.violating.ordering.presentation.web;

import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/** 違反：httpErrorsAreMappedOnlyInErrorPresentationWeb（例外が自分で HTTP のステータスを持つ）。 */
class OrderRejectedException extends ErrorResponseException {

  @Serial private static final long serialVersionUID = 1L;

  /** 422 の例外を作る。 */
  /* package */ OrderRejectedException() {
    super(HttpStatus.UNPROCESSABLE_CONTENT);
  }
}
