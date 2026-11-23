package com.example.demo.error.presentation.web;

import com.example.demo.LocaleSupport;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** サーブレットコンテナから {@code /error} へ転送された失敗を Problem Details で返す。 */
@Hidden
@RestController
public class ApiErrorController implements ErrorController {

  /** Problem Details の共通フィールドを生成する。 */
  private final ApiProblemDetails problemDetails;

  /** Problem Details の共通処理を受け取る。 */
  public ApiErrorController(final ApiProblemDetails problemDetails) {
    this.problemDetails = problemDetails;
  }

  /** 転送元の 4xx と 5xx の HTTP status を保ち、内部例外を公開せずにエラーを返す。 */
  // /error はコンテナが元リクエストの method のまま転送する dispatch 先で、状態変更のない読み取り専用のため
  // method を絞らない（Spring の BasicErrorController も同様）。CSRF の懸念はない。
  // nosemgrep: java.spring.security.unrestricted-request-mapping.unrestricted-request-mapping
  @RequestMapping(path = "${spring.web.error.path:${error.path:/error}}")
  public ResponseEntity<ProblemDetail> error(final HttpServletRequest request) {
    final @Nullable Object statusAttribute =
        request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    final HttpStatusCode status = resolveStatus(statusAttribute);
    final Locale locale = LocaleSupport.resolve(request);
    return ResponseEntity.status(status)
        .headers(ApiProblemDetails.responseHeaders(new HttpHeaders(), status, locale))
        .body(problemDetails.localizedForStatus(status, locale));
  }

  /** 4xx と 5xx は HttpStatus に定義がなくても保ち、それ以外は 500 にする（ADR-013）。 */
  private static HttpStatusCode resolveStatus(final @Nullable Object statusAttribute) {
    if (statusAttribute instanceof Integer code) {
      final HttpStatus.@Nullable Series series = HttpStatus.Series.resolve(code);
      if (series == HttpStatus.Series.CLIENT_ERROR || series == HttpStatus.Series.SERVER_ERROR) {
        return HttpStatusCode.valueOf(code);
      }
    }
    return HttpStatus.INTERNAL_SERVER_ERROR;
  }
}
