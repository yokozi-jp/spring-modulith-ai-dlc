package archfixture.conforming.ordering.application;

import archfixture.conforming.ordering.domain.model.OrderId;
import archfixture.conforming.ordering.domain.model.PaymentGateway;
import com.example.demo.shared.security.NoRoleRequired;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 外部の決済システムを呼んで注文の代金を請求する。{@link OrderPlacedPaymentListener} だけが呼ぶ。 */
@Service
public class CapturePaymentCommandHandler {

  /** 外部の決済システム。 */
  private final PaymentGateway paymentGateway;

  /** 依存を受け取る。 */
  public CapturePaymentCommandHandler(final PaymentGateway paymentGateway) {
    this.paymentGateway = paymentGateway;
  }

  /** 注文の代金を請求する。 */
  @NoRoleRequired(NoRoleRequired.Basis.CALLED_WITHOUT_AUTHENTICATION)
  @Transactional
  public CapturePaymentResult handle(final CapturePaymentCommand command) {
    return new CapturePaymentResult(
        paymentGateway.charge(new OrderId(UUID.fromString(command.orderId()))).value());
  }
}
