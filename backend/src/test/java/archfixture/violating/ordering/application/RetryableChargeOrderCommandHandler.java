package archfixture.violating.ordering.application;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.PaymentGateway;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 違反：retryableIsNotUsedOnExternalSystemPath（外部システムを呼ぶ CommandHandler のクラスに {@code @Retryable}
 * を付ける）。
 */
@Service
@Retryable
public class RetryableChargeOrderCommandHandler {

  /** 外部の決済システム。 */
  private final PaymentGateway paymentGateway;

  /** 依存を受け取る。 */
  public RetryableChargeOrderCommandHandler(final PaymentGateway paymentGateway) {
    this.paymentGateway = paymentGateway;
  }

  /** 注文の代金を請求し、決済 ID を返す。 */
  @Transactional
  public ChargeOrderResult handle(final ChargeOrderCommand command) {
    return new ChargeOrderResult(paymentGateway.charge(new OrderId(command.orderId())));
  }
}
