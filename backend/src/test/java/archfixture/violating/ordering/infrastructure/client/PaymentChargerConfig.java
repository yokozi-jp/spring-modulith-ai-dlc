package archfixture.violating.ordering.infrastructure.client;

import archfixture.violating.ordering.domain.model.PaymentCharger;
import archfixture.violating.ordering.domain.model.PaymentGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 違反：externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients（外部システムのインタフェースを実装しない設定クラスが、infrastructure.client
 * に置いたことを理由に依存する）。
 */
@Configuration
public class PaymentChargerConfig {

  /** 外部システムを包む {@link PaymentCharger} を登録する。 */
  @Bean
  public PaymentCharger paymentCharger(final PaymentGateway paymentGateway) {
    return new PaymentCharger(paymentGateway);
  }
}
