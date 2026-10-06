package archfixture.violating.shared.infrastructure.persistence;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 違反：sharedModuleDoesNotDependOnHttp（shared の共通処理が HTTP の例外を投げる）。 */
public final class ChildRowWriter {

  /** 子の行が見つからないことを、HTTP の 422 の例外で投げる。 */
  public void updateChild(final int updated) {
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT);
    }
  }
}
