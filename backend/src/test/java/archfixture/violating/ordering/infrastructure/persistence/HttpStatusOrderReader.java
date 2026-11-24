package archfixture.violating.ordering.infrastructure.persistence;

import org.springframework.http.HttpStatus;

/** 違反：httpTypesAreUsedOnlyByHttpLayers（infrastructure.persistence が HTTP のステータスを使う）。 */
public final class HttpStatusOrderReader {

  /** 注文が見つからないときの HTTP のステータスを返す。 */
  public HttpStatus statusWhenMissing() {
    return HttpStatus.NOT_FOUND;
  }
}
