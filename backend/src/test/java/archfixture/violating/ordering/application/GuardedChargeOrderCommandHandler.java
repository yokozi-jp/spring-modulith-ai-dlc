package archfixture.violating.ordering.application;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 違反：circuitBreakerIsUsedOnlyOnClients（CommandHandler のメソッドに {@code @CircuitBreaker} を付ける）。 */
@Service
public class GuardedChargeOrderCommandHandler {

  /** 注文の代金の請求を受け付ける。 */
  @CircuitBreaker(name = "payment-gateway")
  @Transactional
  public ChargeOrderResult handle(final ChargeOrderCommand command) {
    return new ChargeOrderResult("payment-" + command.orderId());
  }
}
