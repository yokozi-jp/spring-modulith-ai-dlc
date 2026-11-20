package archfixture.violating.ordering.presentation.web;

import archfixture.violating.ordering.application.ChargeOrderCommand;
import archfixture.violating.ordering.application.ChargeOrderCommandHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 違反：externalSystemCommandHandlersAreDependedOnlyByListeners（外部システムに依存する CommandHandler を画面から呼ぶ）。
 */
@RestController
@RequestMapping("/api/order-charges")
class ChargeOrderController {

  /** 外部システムのインタフェースに依存する CommandHandler。 */
  private final ChargeOrderCommandHandler chargeOrder;

  /** 依存を受け取る。 */
  /* package */ ChargeOrderController(final ChargeOrderCommandHandler chargeOrder) {
    this.chargeOrder = chargeOrder;
  }

  /** 注文の代金を請求する。 */
  @PostMapping("/{orderId}")
  /* package */ void charge(@PathVariable final String orderId) {
    chargeOrder.handle(new ChargeOrderCommand(orderId));
  }
}
