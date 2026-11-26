package archfixture.violating.ordering.application;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.PaymentGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 外部システムのインタフェースに依存する CommandHandler。違反：springRetryIsNotUsedOnExternalSystemPath（{@code handle} に
 * {@code @Retryable} の合成アノテーションを付ける）。
 *
 * <p>externalSystemCommandHandlersAreDependedOnlyByListeners が、この CommandHandler を呼ぶ {@code
 * ChargeOrderController} と {@code ChargeOrderListener} を検出する。
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
  @RetryOnFailure
  @Transactional
  public ChargeOrderResult handle(final ChargeOrderCommand command) {
    return new ChargeOrderResult(paymentGateway.charge(new OrderId(command.orderId())));
  }
}
