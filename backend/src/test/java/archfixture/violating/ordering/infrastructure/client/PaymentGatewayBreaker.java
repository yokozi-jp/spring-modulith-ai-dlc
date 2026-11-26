package archfixture.violating.ordering.infrastructure.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

/** 違反：circuitBreakerIsUsedOnlyOnClients（{@code *Client} でないクラスに {@code @CircuitBreaker} を付ける）。 */
@CircuitBreaker(name = "payment-gateway")
public class PaymentGatewayBreaker {

  /** 決済の可否を返す。 */
  public boolean available() {
    return true;
  }
}
