package archfixture.violating.ordering.domain.model;

import org.springframework.stereotype.Component;

/** 違反：domainModelDependsOnlyOnAllowedTypes（domain.model に @Component を付ける）。 */
@Component
public class OrderFactory {

  /** 注文 ID を作る。 */
  public OrderId create(final String value) {
    return new OrderId(value);
  }
}
