package com.example.demo.payment.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.ResourceAccessException;

/** 決済代行の偽物の振る舞いと、設定値の検査を検証する。 */
// 起動の確認は ApplicationContextRunner の run の中の AssertJ で書く。
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class PaymentGatewayClientTest {

  /** 請求する注文。 */
  private static final OrderId ORDER_ID =
      new OrderId(UUID.fromString("5f0c2a8e-7d1b-4c3a-9e6f-1a2b3c4d5e6f"));

  /** 請求する金額。 */
  private static final Money AMOUNT = new Money(new BigDecimal("240.00"));

  /**
   * placeholder を解決して Client だけを登録する文脈。
   *
   * <p>テストの JVM は {@code .env.test} の {@code PAYMENT_GATEWAY_MODE} を環境変数に持ち、緩い束縛で {@code
   * payment-gateway.mode} に当たるため、環境変数の property source を外す。
   */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withInitializer(
              context ->
                  context
                      .getEnvironment()
                      .getPropertySources()
                      .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
          .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
          .withBean(PaymentGatewayClient.class);

  @Test
  @DisplayName("SUCCEED では、同じ注文に同じ識別子を返す")
  void succeedReturnsSameCodeForSameOrder() {
    final PaymentGatewayClient client = new PaymentGatewayClient("SUCCEED");

    final GatewayPaymentCode first = client.charge(ORDER_ID, AMOUNT);
    final GatewayPaymentCode second = client.charge(ORDER_ID, AMOUNT);

    assertThat(first)
        .as("orderId=%s の識別子", ORDER_ID.value())
        .isEqualTo(new GatewayPaymentCode("fake-" + ORDER_ID.value()))
        .isEqualTo(second);
  }

  @Test
  @DisplayName("FAIL では、通信の失敗と同じ ResourceAccessException を注文 ID 付きで投げる")
  void failThrowsResourceAccessException() {
    final PaymentGatewayClient client = new PaymentGatewayClient("FAIL");

    assertThatThrownBy(() -> client.charge(ORDER_ID, AMOUNT))
        .isInstanceOf(ResourceAccessException.class)
        .hasMessageContaining("orderId=" + ORDER_ID.value());
  }

  @ParameterizedTest
  @ValueSource(strings = {"succeed", "", "UNKNOWN"})
  @DisplayName("SUCCEED と FAIL 以外の設定値は、値を含む IllegalStateException で作成を失敗させる")
  void rejectsUnknownMode(final String mode) {
    assertThatThrownBy(() -> new PaymentGatewayClient(mode))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("payment-gateway.mode must be SUCCEED or FAIL: mode=" + mode);
  }

  @Test
  @DisplayName("設定のキーがなければ、placeholder の解決の失敗で起動に失敗する")
  void missingModeFailsStartup() {
    contextRunner.run(
        context ->
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                .hasMessageContaining("Could not resolve placeholder 'payment-gateway.mode'"));
  }

  @Test
  @DisplayName("設定値が未知の値なら、値を含む IllegalStateException で起動に失敗する")
  void unknownModeFailsStartup() {
    contextRunner
        .withPropertyValues("payment-gateway.mode=sometimes")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining(
                        "IllegalStateException: payment-gateway.mode must be SUCCEED or FAIL:"
                            + " mode=sometimes"));
  }

  @Test
  @DisplayName("設定値が SUCCEED なら起動する")
  void succeedModeStarts() {
    contextRunner
        .withPropertyValues("payment-gateway.mode=SUCCEED")
        .run(context -> assertThat(context).hasSingleBean(PaymentGatewayClient.class));
  }
}
