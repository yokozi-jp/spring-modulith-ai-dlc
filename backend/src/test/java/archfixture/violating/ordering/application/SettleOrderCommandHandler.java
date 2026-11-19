package archfixture.violating.ordering.application;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.service.PaymentChargePolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 違反：externalSystemCommandHandlersAreCalledOnlyByListeners（Domain Service を通して外部システムに依存し、Controller
 * から呼ばれる）。
 */
@Service
public class SettleOrderCommandHandler {

  /** 外部システムを呼ぶ Domain Service。 */
  private final PaymentChargePolicy paymentChargePolicy;

  /** 依存を受け取る。 */
  public SettleOrderCommandHandler(final PaymentChargePolicy paymentChargePolicy) {
    this.paymentChargePolicy = paymentChargePolicy;
  }

  /** 注文の代金を請求し、決済 ID を返す。 */
  @Transactional
  public String handle(final String orderId) {
    return paymentChargePolicy.charge(new OrderId(orderId));
  }
}
