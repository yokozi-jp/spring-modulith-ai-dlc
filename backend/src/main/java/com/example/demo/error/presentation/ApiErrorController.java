package com.example.demo.error.presentation;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** サーブレットコンテナから {@code /error} へ転送された失敗を Problem Details で返す。 */
@Hidden
@RestController
public class ApiErrorController implements ErrorController {

  /** 転送元の HTTP status を保ち、内部例外を公開せずにエラーを返す。 */
  // /error はコンテナが元リクエストの method のまま転送する dispatch 先で、状態変更のない読み取り専用のため
  // method を絞らない（Spring の BasicErrorController も同様）。CSRF の懸念はない。
  // nosemgrep: java.spring.security.unrestricted-request-mapping.unrestricted-request-mapping
  @RequestMapping(
      path = "${spring.web.error.path:${error.path:/error}}",
      produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
  public ResponseEntity<ProblemDetail> error(final HttpServletRequest request) {
    final @Nullable Object statusAttribute =
        request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    final HttpStatus status = resolveStatus(statusAttribute);
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ApiProblemDetails.forStatus(status));
  }

  private static HttpStatus resolveStatus(final @Nullable Object statusAttribute) {
    return Optional.ofNullable(statusAttribute)
        .filter(Integer.class::isInstance)
        .map(Integer.class::cast)
        .map(code -> HttpStatus.resolve(code))
        .orElse(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
