package com.example.demo.payment.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.payment.domain.model.ChargeOutcome;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** 決済代行の Client の HTTP の呼び出しと、URL の設定の検査を検証する。 */
// 起動の確認は ApplicationContextRunner の run の中の AssertJ で書く。
// 成功、拒否、4xx の分類、契約に合わない応答、5xx、タイムアウト、URL の設定を一つの HttpServer の文脈で確かめるため、メソッドが多い。
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.TooManyMethods"})
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

    final ChargeOutcome outcome = client.charge(ORDER_ID, AMOUNT);

    assertThat(outcome)
        .as("orderId=%s の請求の結果", ORDER_ID.value())
        .isEqualTo(ChargeOutcome.paid(new GatewayPaymentCode("ch_1")));
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
  @DisplayName("状態が DECLINED なら、例外を投げずに識別子付きの拒否を返す")
  void declinedReturnsDeclined() throws IOException {
    final PaymentGatewayClient client =
        start(exchange -> reply(exchange, 201, "{\"chargeId\":\"ch_1\",\"status\":\"DECLINED\"}"));

    assertThat(client.charge(ORDER_ID, AMOUNT))
        .as("orderId=%s の請求の結果", ORDER_ID.value())
        .isEqualTo(ChargeOutcome.declined(new GatewayPaymentCode("ch_1")));
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '\'',
      value = {
        "201 | ''",
        "200 | {\"chargeId\":\"ch_1\",\"status\":\"SUCCEEDED\"}",
        "202 | {\"chargeId\":\"ch_1\",\"status\":\"SUCCEEDED\"}",
        "200 | {\"chargeId\":\"ch_1\",\"status\":\"DECLINED\"}",
        "201 | {\"chargeId\":\"ch_1\",\"status\":\"PENDING\"}",
        "201 | {\"chargeId\":\"ch_1\"}",
        "201 | {\"chargeId\":\" \",\"status\":\"SUCCEEDED\"}",
        "201 | {\"status\":\"SUCCEEDED\"}",
        "201 | {\"status\":\"DECLINED\"}",
        "201 | {\"chargeId\":\"\",\"status\":\"DECLINED\"}",
        "201 | {\"chargeId\":",
      })
  @DisplayName("契約に合わない応答は契約の不備なので、例外を投げずに失敗を返す")
  void contractViolatingReplyReturnsFailed(final int status, final String body) throws IOException {
    final PaymentGatewayClient client = start(exchange -> reply(exchange, status, body));

    assertThat(client.charge(ORDER_ID, AMOUNT))
        .as("orderId=%s の %d %s の請求の結果", ORDER_ID.value(), status, body)
        .isEqualTo(ChargeOutcome.failed());
  }

  @Test
  @DisplayName("本文が JSON でなければ、契約の不備なので、例外を投げずに失敗を返す")
  void nonJsonReplyReturnsFailed() throws IOException {
    final PaymentGatewayClient client =
        start(
            exchange -> {
              final byte[] bytes = "ok".getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().add("Content-Type", "text/plain");
              exchange.sendResponseHeaders(201, bytes.length);
              try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
              }
            });

    assertThat(client.charge(ORDER_ID, AMOUNT))
        .as("orderId=%s の text/plain の請求の結果", ORDER_ID.value())
        .isEqualTo(ChargeOutcome.failed());
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 404, 409, 422})
  @DisplayName("401、403、429 以外の 4xx は契約の不備なので、例外を投げずに失敗を返す")
  void contractErrorReturnsFailed(final int status) throws IOException {
    final PaymentGatewayClient client = start(exchange -> reply(exchange, status, ""));

    assertThat(client.charge(ORDER_ID, AMOUNT))
        .as("orderId=%s の %d の請求の結果", ORDER_ID.value(), status)
        .isEqualTo(ChargeOutcome.failed());
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 429})
  @DisplayName("401、403、429 は再投入で回復できるので、HttpClientErrorException を投げる")
  void recoverableClientErrorThrows(final int status) throws IOException {
    final PaymentGatewayClient client = start(exchange -> reply(exchange, status, ""));

    assertThatThrownBy(() -> client.charge(ORDER_ID, AMOUNT))
        .isInstanceOf(HttpClientErrorException.class);
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
