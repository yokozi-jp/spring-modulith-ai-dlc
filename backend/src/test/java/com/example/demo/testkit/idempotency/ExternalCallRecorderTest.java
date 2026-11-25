package com.example.demo.testkit.idempotency;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import archfixture.violating.ordering.infrastructure.client.ShippingLabelClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link ExternalCallRecorder} が、記録できない外部システムの呼び出しを黙って通さないことを確かめる。 */
class ExternalCallRecorderTest {

  @Test
  @DisplayName("domain.model のインタフェースを実装しない infrastructure.client の *Client は、包まずに通さず、文脈の起動を失敗させる")
  void clientWithoutDomainModelInterfaceFails() {
    assertThatThrownBy(() ->
            new ExternalCallRecorder()
                .postProcessAfterInitialization(new ShippingLabelClient(), "shippingLabelClient"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(ShippingLabelClient.class.getName());
  }
}
