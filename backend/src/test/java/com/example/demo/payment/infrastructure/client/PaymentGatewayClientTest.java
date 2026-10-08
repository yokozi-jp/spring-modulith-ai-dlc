package com.example.demo.payment.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** 決済代行の Client の HTTP の呼び出しと、URL の設定の検査を検証する。 */
// 起動の確認は ApplicationContextRunner の run の中の AssertJ で書く。
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class PaymentGatewayClientTest {

  /** 請求する注文。 */
  private static final OrderId ORDER_ID =
      new OrderId(UUID.fromString("5f0c2a8e-7d1b-4c3a-9e6f-1a2b3c4d5e6f"));

  /** 請求する金額。 */
  private static final Money AMOUNT = new Money(new BigDecimal("240.00"));

  /** 決済代行の成功の応答。 */
  private static final String SUCCEEDED_REPLY = "{\"chargeId\":\"ch_1\",\"status\":\"SUCCEEDED\"}";

  /**
   * placeholder を解決して Client だけを登録する文脈。
   *
   * <p>テストの JVM は {@code .env.test} の {@code PAYMENT_GATEWAY_BASE_URL} を環境変数に持ち、緩い束縛で {@code
   * payment-gateway.base-url} に当たるため、環境変数の property source を外す。
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

  /** テストごとに起動する決済代行の代わりの HTTP サーバ。 */
  private @Nullable HttpServer server;

  /** 起動した HTTP サーバを止める。 */
  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("注文 ID を冪等性キーにして請求の本文を送り、決済代行が返した識別子を返す")
  void chargesWithOrderIdAsIdempotencyKey() throws IOException {
    final AtomicReference<String> method = new AtomicReference<>();
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();
    final PaymentGatewayClient client =
        start(
            exchange -> {
              method.set(exchange.getRequestMethod());
              path.set(exchange.getRequestURI().getPath());
              idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
              body.set(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
              reply(exchange, 201, SUCCEEDED_REPLY);
            });

    final GatewayPaymentCode code = client.charge(ORDER_ID, AMOUNT);

    assertThat(code)
        .as("orderId=%s の識別子", ORDER_ID.value())
        .isEqualTo(new GatewayPaymentCode("ch_1"));
    assertThat(method).as("orderId=%s の請求のメソッド", ORDER_ID.value()).hasValue("POST");
    assertThat(path).as("orderId=%s の請求のパス", ORDER_ID.value()).hasValue("/v1/charges");
    assertThat(idempotencyKey)
        .as("orderId=%s の請求の冪等性キー", ORDER_ID.value())
        .hasValue(ORDER_ID.value().toString());
    assertThat(body.get())
        .as("orderId=%s の請求の本文", ORDER_ID.value())
        .contains("\"orderId\":\"" + ORDER_ID.value() + "\"")
        .contains("\"amount\":240.00")
        .contains("\"currency\":\"JPY\"");
  }

  @Test
  @DisplayName("成功の応答に本文がなければ、IllegalStateException を投げる")
  void rejectsEmptyBody() throws IOException {
    final PaymentGatewayClient client = start(exchange -> reply(exchange, 201, ""));

    assertThatThrownBy(() -> client.charge(ORDER_ID, AMOUNT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("payment gateway returned no body: orderId=" + ORDER_ID.value());
  }

  @Test
  @DisplayName("状態が SUCCEEDED でなければ、状態を含む IllegalStateException を投げる")
  void rejectsNotSucceeded() throws IOException {
    final PaymentGatewayClient client =
        start(exchange -> reply(exchange, 201, "{\"chargeId\":\"ch_1\",\"status\":\"DECLINED\"}"));

    assertThatThrownBy(() -> client.charge(ORDER_ID, AMOUNT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "payment gateway did not succeed: orderId=" + ORDER_ID.value() + ", status=DECLINED");
  }

  @Test
  @DisplayName("決済代行が 5xx を返せば、HttpServerErrorException を投げる")
  void serverErrorThrows() throws IOException {
    final PaymentGatewayClient client = start(exchange -> reply(exchange, 503, ""));

    assertThatThrownBy(() -> client.charge(ORDER_ID, AMOUNT))
        .isInstanceOf(HttpServerErrorException.class);
  }

  @Test
  @DisplayName("応答が呼び出しのタイムアウト 2 秒を超えれば、ResourceAccessException を投げる")
  // 応答を止める間に割り込まれたら、割り込みの状態を戻す。
  @SuppressWarnings("PMD.DoNotUseThreads")
  void slowReplyTimesOut() throws IOException {
    final CountDownLatch release = new CountDownLatch(1);
    final PaymentGatewayClient client =
        start(
            exchange -> {
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              reply(exchange, 201, SUCCEEDED_REPLY);
            });
    try {
      final long started = System.nanoTime();
      assertThatThrownBy(() -> client.charge(ORDER_ID, AMOUNT))
          .isInstanceOf(ResourceAccessException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - started))
          .as("orderId=%s の請求がタイムアウトするまでの時間", ORDER_ID.value())
          .isLessThan(Duration.ofSeconds(4));
    } finally {
      release.countDown();
    }
  }

  @Test
  @DisplayName("URL の設定がなければ、placeholder の解決の失敗で起動に失敗する")
  void missingBaseUrlFailsStartup() {
    contextRunner.run(
        context ->
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                .hasMessageContaining("Could not resolve placeholder 'payment-gateway.base-url'"));
  }

  @Test
  @DisplayName("URL の設定があれば起動する")
  void baseUrlStarts() {
    contextRunner
        .withPropertyValues("payment-gateway.base-url=http://127.0.0.1:1")
        .run(context -> assertThat(context).hasSingleBean(PaymentGatewayClient.class));
  }

  /** ループバックの空いているポートで HTTP サーバを起動し、その URL を指す Client を返す。 */
  private PaymentGatewayClient start(final HttpHandler handler) throws IOException {
    final HttpServer started =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    started.createContext("/v1/charges", handler);
    started.start();
    server = started;
    return new PaymentGatewayClient(
        "http://"
            + InetAddress.getLoopbackAddress().getHostAddress()
            + ":"
            + started.getAddress().getPort());
  }

  /** JSON の本文で応答する。本文が空なら本文を送らない。 */
  private static void reply(final HttpExchange exchange, final int status, final String body)
      throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
