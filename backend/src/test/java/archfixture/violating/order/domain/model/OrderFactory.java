package archfixture.violating.order.domain.model;

import org.springframework.stereotype.Component;

/** 違反：domainModelDoesNotDependOnFrameworks（domain.model に @Component を付ける）。 */
@Component
public class OrderFactory {

  /** 注文 ID を作る。 */
  public OrderId create(final String value) {
    return new OrderId(value);
  }
}
