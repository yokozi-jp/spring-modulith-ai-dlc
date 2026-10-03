package archfixture.violating.order.application;

import org.springframework.stereotype.Service;

/** 違反：commandHandlersDoNotDependOnOtherCommandHandlers（別の CommandHandler を呼ぶ）。 */
@Service
public class ConfirmOrderCommandHandler {

  /** 呼んではいけない別の CommandHandler。 */
  private final CancelOrderCommandHandler cancelOrder;

  /** 別の CommandHandler を受け取る。 */
  public ConfirmOrderCommandHandler(final CancelOrderCommandHandler cancelOrder) {
    this.cancelOrder = cancelOrder;
  }

  /** 注文を確定する代わりに取り消す。 */
  public String handle(final ConfirmOrderCommand command) {
    return cancelOrder.execute(command.getOrderId());
  }
}
