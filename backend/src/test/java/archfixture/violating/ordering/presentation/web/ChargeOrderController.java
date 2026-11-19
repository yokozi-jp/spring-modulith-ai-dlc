package archfixture.violating.ordering.presentation.web;

import archfixture.violating.ordering.application.ChargeOrderCommandHandler;
import archfixture.violating.ordering.application.SettleOrderCommandHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 違反：externalSystemCommandHandlersAreCalledOnlyByListeners（外部システムに依存する CommandHandler を画面から呼ぶ）。 */
@RestController
@RequestMapping("/api/order-charges")
class ChargeOrderController {

  /** 外部システムのインタフェースに直接依存する CommandHandler。 */
  private final ChargeOrderCommandHandler chargeOrder;

  /** Domain Service を通して外部システムに依存する CommandHandler。 */
  private final SettleOrderCommandHandler settleOrder;

  /** 依存を受け取る。 */
  /* package */ ChargeOrderController(
      final ChargeOrderCommandHandler chargeOrder, final SettleOrderCommandHandler settleOrder) {
    this.chargeOrder = chargeOrder;
    this.settleOrder = settleOrder;
  }

  /** 注文の代金を請求する。 */
  @PostMapping("/{orderId}")
  /* package */ void charge(@PathVariable final String orderId) {
    chargeOrder.handle(orderId);
    settleOrder.handle(orderId);
  }
}
