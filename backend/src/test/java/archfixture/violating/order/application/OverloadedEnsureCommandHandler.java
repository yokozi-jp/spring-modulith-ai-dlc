package archfixture.violating.order.application;

import archfixture.violating.order.domain.model.Order;
import archfixture.violating.order.domain.model.OrderId;
import org.springframework.stereotype.Service;

/** 違反：commandHandlersEnsureScreenLockNo（ensureLockNo(long) のオーバーロードだけを呼び、ExpectedLockNo を渡さない）。 */
@Service
public class OverloadedEnsureCommandHandler {

  /** 版の数値だけを比べて、注文 ID を返す。 */
  public String handle(final ApproveOrderCommand command) {
    final Order order = new Order(new OrderId(command.orderId()), 1L);
    order.ensureLockNo(command.expectedLockNo().value());
    return order.id().value();
  }
}
