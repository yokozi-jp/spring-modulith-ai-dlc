package archfixture.violating.ordering.domain.model;

import lombok.extern.slf4j.Slf4j;

/** 違反：domainModelDependsOnlyOnAllowedTypes（domain.model が SLF4J の Logger を使う）。 */
@Slf4j
public final class LoggingOrder {

  /** 注文を受け付けたことを記録する。 */
  public void accept() {
    log.info("accepted");
  }
}
