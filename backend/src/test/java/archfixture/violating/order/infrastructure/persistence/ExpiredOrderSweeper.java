package archfixture.violating.order.infrastructure.persistence;

import archfixture.violating.order.application.CancelOrderCommandHandler;
import org.springframework.stereotype.Component;

/**
 * 違反：infrastructureDependsOnlyOnDomainModel（Infrastructure から Application の CommandHandler を呼ぶ）。
 */
@Component
public class ExpiredOrderSweeper {

  /** 呼んではいけない Application の CommandHandler。 */
  private final CancelOrderCommandHandler cancelOrder;

  /** Application の CommandHandler を受け取る。 */
  public ExpiredOrderSweeper(final CancelOrderCommandHandler cancelOrder) {
    this.cancelOrder = cancelOrder;
  }

  /** 期限切れの注文を取り消す。 */
  public String sweep(final String orderId) {
    return cancelOrder.execute(orderId);
  }
}
