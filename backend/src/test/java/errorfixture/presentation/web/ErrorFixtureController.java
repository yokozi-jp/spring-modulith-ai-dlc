package errorfixture.presentation.web;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** エラー応答の検証用の HTTP API。例外を起こすためだけに使う。 */
@RestController
@RequestMapping("/api/error-fixture")
class ErrorFixtureController {

  /** ConstraintViolationException を作る Validator。 */
  private final Validator validator;

  /** アプリケーションの Validator を受け取る。 */
  /* package */ ErrorFixtureController(final Validator validator) {
    this.validator = validator;
  }

  /**
   * 本文を検証する。
   *
   * @param request 検証する本文
   * @return 本文のない 204 の応答
   */
  @PostMapping("/items")
  /* package */ ResponseEntity<Void> create(@Valid @RequestBody final ErrorFixtureRequest request) {
    return ResponseEntity.noContent().build();
  }

  /**
   * クエリパラメータを検証する。
   *
   * @param limit 1 以上の件数
   * @return 本文のない 204 の応答
   */
  @GetMapping("/items")
  /* package */ ResponseEntity<Void> search(@RequestParam @Min(1) final int limit) {
    return ResponseEntity.noContent().build();
  }

  /**
   * 本文とほかの制約のある引数を、メソッド検証でまとめて検証する。
   *
   * @param request 検証する本文
   * @param limit 1 以上の件数
   * @return 本文のない 204 の応答
   */
  @PostMapping("/items-with-limit")
  /* package */ ResponseEntity<Void> createWithLimit(
      @Valid @RequestBody final ErrorFixtureRequest request,
      @RequestParam @Min(1) final int limit) {
    return ResponseEntity.noContent().build();
  }

  /**
   * クエリパラメータのリストの要素を検証する。
   *
   * @param names 空でない名前のリスト
   * @return 本文のない 204 の応答
   */
  @GetMapping("/names")
  /* package */ ResponseEntity<Void> names(@RequestParam final List<@NotBlank String> names) {
    return ResponseEntity.noContent().build();
  }

  /**
   * 引数をまたぐ制約を検証する。
   *
   * @param from 範囲の始まり
   * @param to 範囲の終わり
   * @return 本文のない 204 の応答
   */
  @GetMapping("/range")
  @ErrorFixtureRange
  /* package */ ResponseEntity<Void> range(
      @RequestParam @Min(0) final int from, @RequestParam @Min(0) final int to) {
    return ResponseEntity.noContent().build();
  }

  /**
   * Validator の違反を ConstraintViolationException として投げる。
   *
   * @return 返さない
   */
  @GetMapping("/constraint-violation")
  /* package */ ResponseEntity<Void> constraintViolation() {
    throw new ConstraintViolationException(
        validator.validate(new ErrorFixtureItem("too-long-code")));
  }

  /**
   * 未処理の例外を投げる。
   *
   * @return 返さない
   */
  @GetMapping("/unhandled")
  /* package */ ResponseEntity<Void> unhandled() {
    throw new IllegalStateException("fixture failure");
  }

  /**
   * LockedRoot.updateChild の 0 件と同じく、テーブルとキーを reason に持つ 422 を投げる。
   *
   * @return 返さない
   */
  @GetMapping("/unprocessable")
  /* package */ ResponseEntity<Void> unprocessable() {
    throw new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_CONTENT, "child row not found: fixture_child id=secret-key");
  }
}
