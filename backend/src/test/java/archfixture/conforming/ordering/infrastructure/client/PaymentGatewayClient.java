package archfixture.conforming.ordering.infrastructure.client;

import archfixture.conforming.ordering.domain.model.OrderId;
import archfixture.conforming.ordering.domain.model.PaymentGateway;
import archfixture.conforming.ordering.domain.model.PaymentId;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 決済システムの Client。フィクスチャなので HTTP を呼ばずに決済 ID を作る。circuit breaker は Client のメソッドに付ける。 */
@Component
class PaymentGatewayClient implements PaymentGateway {

  /** 決済システムの URL。 */
  private final String baseUrl;

  /** 決済システムの URL を受け取る。 */
  /* package */ PaymentGatewayClient(@Value("${payment-gateway.base-url}") final String baseUrl) {
    this.baseUrl = baseUrl;
  }

  @CircuitBreaker(name = "payment-gateway")
  @Override
  public PaymentId charge(final OrderId orderId) {
    return new PaymentId(baseUrl + "/payments/" + orderId.value());
  }
}
