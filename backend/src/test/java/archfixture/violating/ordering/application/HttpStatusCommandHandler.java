package archfixture.violating.ordering.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 違反：httpTypesAreNotUsedByInnerLayers（application の CommandHandler が HTTP の例外を投げる）。 */
@Component
public class HttpStatusCommandHandler {

  /** 注文が見つからないことを、HTTP の 404 の例外で投げる。 */
  public void handle() {
    throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
