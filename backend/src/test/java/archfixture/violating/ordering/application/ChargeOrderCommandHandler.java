package archfixture.violating.ordering.application;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.PaymentGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 違反：externalSystemCommandHandlersAreDependedOnlyByListeners（外部システムのインタフェースに依存し、Controller から呼ばれる）。
 */
@Service
public class ChargeOrderCommandHandler {

  /** 外部の決済システム。 */
  private final PaymentGateway paymentGateway;

  /** 依存を受け取る。 */
  public ChargeOrderCommandHandler(final PaymentGateway paymentGateway) {
    this.paymentGateway = paymentGateway;
  }

  /** 注文の代金を請求し、決済 ID を返す。 */
  @Transactional
  public ChargeOrderResult handle(final ChargeOrderCommand command) {
    return new ChargeOrderResult(paymentGateway.charge(new OrderId(command.orderId())));
  }
}
