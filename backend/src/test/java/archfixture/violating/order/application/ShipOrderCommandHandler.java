package archfixture.violating.order.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 違反：transactionalIsNotDeclaredAtClassLevel（クラスに @Transactional を付ける）。 */
@Service
@Transactional
public class ShipOrderCommandHandler {

  /** 出荷する注文 ID を正規化して返す。 */
  public String ship(final String orderId) {
    return orderId.trim();
  }
}
