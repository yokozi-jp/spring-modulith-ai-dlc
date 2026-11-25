package archfixture.violating.ordering.presentation.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 違反：httpErrorsAreMappedOnlyInErrorPresentationWeb（機能モジュールが応答の status と本文を決める）。 */
class OrderResponseStatuses {

  /** 422 の応答を、HttpStatus の定数から作る。 */
  /* package */ ResponseEntity<Void> unprocessable() {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).build();
  }

  /** 409 の応答を、HttpStatus の定数から作る。 */
  /* package */ ResponseEntity<Void> conflict() {
    return ResponseEntity.status(HttpStatus.CONFLICT).build();
  }

  /** 失敗の Problem Details を、ErrorResponse の builder で作る。 */
  /* package */ ErrorResponse rejected(final Exception exception) {
    return ErrorResponse.builder(exception, HttpStatusCode.valueOf(400), "rejected").build();
  }

  /** 成功の応答の status を注釈で決める。 */
  @ResponseStatus(HttpStatus.CREATED)
  /* package */ void created() {
    // 注釈の違反だけを示すため、本文を持たない。
  }

  /** 値を省いた注釈で応答の status を決める。既定の値は 500 である。 */
  @ResponseStatus
  /* package */ void unspecified() {
    // 注釈の違反だけを示すため、本文を持たない。
  }
}
