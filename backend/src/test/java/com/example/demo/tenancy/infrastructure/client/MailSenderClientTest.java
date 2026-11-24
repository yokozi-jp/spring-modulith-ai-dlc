package com.example.demo.tenancy.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.tenancy.domain.model.MailBody;
import com.example.demo.tenancy.domain.model.MailMessageId;
import com.example.demo.tenancy.domain.model.MailOutcome;
import com.example.demo.tenancy.domain.model.MailRecipient;
import com.example.demo.tenancy.domain.model.MailSubject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.StandardEnvironment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * メールの送信の Client の SES v2 の API の呼び出しと、結果の分け方を、JDK の HttpServer で検証する。
 *
 * <p>Client は {@link SesV2ClientConfig#build} と同じタイムアウトと試行 1 回の SDK の Client で呼ぶ。接続先、リージョン、資格情報だけを
 * builder で足す。Spring を起動しないため、circuit breaker と retry は働かない（WireMock の結合テストで確かめる）。
 */
// 成功、拒否、契約に合わない応答、スロットリング、5xx、403、タイムアウト、設定を一つの HttpServer の文脈で確かめるため、メソッドが多い。
// 起動の確認は ApplicationContextRunner の run の中の AssertJ で書く。
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UnitTestShouldIncludeAssert"})
@ExtendWith(OutputCaptureExtension.class)
class MailSenderClientTest {

  /** SES v2 の SendEmail のパス。 */
  private static final String SEND_EMAIL = "/v2/email/outbound-emails";

  /** 宛先。ログに出ないことを確かめるため、ほかの値と重ならないアドレスにする。 */
  private static final MailRecipient TO = new MailRecipient("invitee-7f3a@example.net");

  /** 件名。 */
  private static final MailSubject SUBJECT = new MailSubject("デモアプリ 招待のお知らせ");

  /** 本文。 */
  private static final MailBody BODY =
      new MailBody("次のリンクから参加してください。\nhttps://app.example.com/invitations/1");

  /** From の表示名。 */
  private static final String FROM_NAME = "デモアプリ";

  /** From のアドレス。 */
  private static final String FROM_ADDRESS = "no-reply@example.com";

  /** 設定セットの名前。 */
  private static final String CONFIGURATION_SET = "app-transactional";

  /** 要求の本文を読む。 */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** テストごとに起動する SES の代わりの HTTP サーバ。 */
  private @Nullable HttpServer server;

  /** テストごとに作る SDK の Client。 */
  private @Nullable SesV2Client sdkClient;

  /**
   * SDK のクラスを読み込んでおく。
   *
   * <p>SDK の最初の呼び出しはクラスの読み込みで遅く、負荷の下では呼び出しのタイムアウト 2 秒を超えて、タイムアウト以外のテストまで失敗させるため、長いタイムアウトの Client で
   * 1 回呼ぶ。
   */
  @BeforeAll
  static void warmUpSdk() throws IOException {
    final HttpServer warmUp =
        serve(exchange -> reply(exchange, 200, null, "{\"MessageId\":\"warm-up\"}"));
    try (SesV2Client client = sesV2Client(warmUp, Duration.ofSeconds(30))) {
      new MailSenderClient(client, FROM_ADDRESS, FROM_NAME, CONFIGURATION_SET)
          .send(TO, SUBJECT, BODY);
    } finally {
      warmUp.stop(0);
    }
  }

  /** 起動した HTTP サーバと SDK の Client を止める。 */
  @AfterEach
  void stop() {
    if (sdkClient != null) {
      sdkClient.close();
    }
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("宛先、件名、本文、From、設定セットを SendEmail に送り、SES が採番した MessageId で送信済みを返す")
  void sendsMailAndReturnsMessageId() throws IOException {
    final AtomicReference<String> method = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();
    final MailSenderClient client =
        start(exchange -> {
          method.set(exchange.getRequestMethod());
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          reply(exchange, 200, null, "{\"MessageId\":\"msg-1\"}");
        });

    final MailOutcome outcome = client.send(TO, SUBJECT, BODY);

    assertThat(outcome)
        .as("SendEmail の結果")
        .isEqualTo(new MailOutcome.Sent(new MailMessageId("msg-1")));
    assertThat(method).as("SendEmail のメソッド").hasValue("POST");
    final JsonNode request = JSON.readTree(body.get());
    assertThat(request.at("/Destination/ToAddresses").size()).as("宛先の数").isEqualTo(1);
    assertThat(request.at("/Destination/ToAddresses/0").asString()).as("宛先").isEqualTo(TO.value());
    assertThat(request.at("/Content/Simple/Subject/Data").asString())
        .as("件名")
        .isEqualTo(SUBJECT.value());
    assertThat(request.at("/Content/Simple/Body/Text/Data").asString())
        .as("本文")
        .isEqualTo(BODY.value());
    assertThat(request.at("/Content/Simple/Body/Html").isMissingNode())
        .as("HTML の本文を送らない")
        .isTrue();
    assertThat(request.at("/Content/Simple/Subject/Charset").asString())
        .as("件名の文字コード")
        .isEqualTo("UTF-8");
    assertThat(request.at("/FromEmailAddress").asString())
        .as("From")
        .isEqualTo(
            "=?UTF-8?B?"
                + Base64.getEncoder().encodeToString(FROM_NAME.getBytes(StandardCharsets.UTF_8))
                + "?= <"
                + FROM_ADDRESS
                + ">");
    assertThat(request.at("/ConfigurationSetName").asString())
        .as("設定セット")
        .isEqualTo(CONFIGURATION_SET);
  }

  @ParameterizedTest
  @ValueSource(strings = {"MessageRejected", "BadRequestException"})
  @DisplayName("同じ要求で回復しない拒否は、例外を投げずに拒否を返し、ログに型とエラーコードだけを出して宛先と文面を出さない")
  void unrecoverableRejectionReturnsRejected(final String errorType, final CapturedOutput output)
      throws IOException {
    final MailSenderClient client =
        start(exchange ->
            reply(
                exchange,
                400,
                errorType,
                "{\"message\":\"Email address is not verified: " + TO.value() + "\"}"));

    assertThat(client.send(TO, SUBJECT, BODY))
        .as("%s の結果", errorType)
        .isEqualTo(new MailOutcome.Rejected());
    assertThat(output.getOut())
        .as("%s のログ", errorType)
        .contains("SES rejected the mail")
        .contains("errorCode=" + errorType)
        .doesNotContain(TO.value())
        .doesNotContain("not verified");
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"MessageId\":\" \"}"})
  @DisplayName("MessageId のない 200 は契約に合わないので、送り直さないよう拒否を返す")
  void replyWithoutMessageIdReturnsRejected(final String body) throws IOException {
    final MailSenderClient client = start(exchange -> reply(exchange, 200, null, body));

    assertThat(client.send(TO, SUBJECT, BODY))
        .as("%s の結果", body)
        .isEqualTo(new MailOutcome.Rejected());
  }

  @Test
  @DisplayName("スロットリングは一時障害なので、SDK の再試行をせずに 1 回だけ呼び、TooManyRequestsException を投げる")
  void throttlingThrowsWithoutRetry() throws IOException {
    final AtomicInteger requests = new AtomicInteger();
    final MailSenderClient client =
        start(exchange -> {
          requests.incrementAndGet();
          reply(
              exchange,
              429,
              "TooManyRequestsException",
              "{\"message\":\"Maximum sending rate exceeded.\"}");
        });

    assertThatThrownBy(() -> client.send(TO, SUBJECT, BODY))
        .isInstanceOf(TooManyRequestsException.class);
    assertThat(requests).as("スロットリングの要求の数").hasValue(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 503})
  @DisplayName("5xx は一時障害なので、SDK の再試行をせずに 1 回だけ呼び、SesV2Exception を投げる")
  void serverErrorThrowsWithoutRetry(final int status) throws IOException {
    final AtomicInteger requests = new AtomicInteger();
    final MailSenderClient client =
        start(exchange -> {
          requests.incrementAndGet();
          reply(exchange, status, null, "");
        });

    assertThatThrownBy(() -> client.send(TO, SUBJECT, BODY))
        .isInstanceOfSatisfying(
            SesV2Exception.class,
            exception -> assertThat(exception.statusCode()).as("状態のコード").isEqualTo(status));
    assertThat(requests).as("%d の要求の数", status).hasValue(1);
  }

  @Test
  @DisplayName("権限の不備（AccessDeniedException）は再投入で回復できるので、SesV2Exception を投げる")
  void accessDeniedThrows() throws IOException {
    final MailSenderClient client =
        start(exchange ->
            reply(
                exchange,
                403,
                "AccessDeniedException",
                "{\"Message\":\"User is not authorized to perform: ses:SendEmail\"}"));

    assertThatThrownBy(() -> client.send(TO, SUBJECT, BODY))
        .isInstanceOfSatisfying(
            SesV2Exception.class,
            exception ->
                assertThat(exception.awsErrorDetails().errorCode())
                    .as("エラーコード")
                    .isEqualTo("AccessDeniedException"));
  }

  @Test
  @DisplayName("応答が呼び出しのタイムアウト 2 秒を超えれば、ApiCallTimeoutException を投げる")
  // 応答を止める間に割り込まれたら、割り込みの状態を戻す。
  @SuppressWarnings("PMD.DoNotUseThreads")
  void slowReplyTimesOut() throws IOException {
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger requests = new AtomicInteger();
    final MailSenderClient client =
        start(exchange -> {
          // 1 回目はすぐに返し、SDK の初回の準備の時間を測る時間から外す。
          if (requests.getAndIncrement() > 0) {
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
            }
          }
          reply(exchange, 200, null, "{\"MessageId\":\"msg-1\"}");
        });
    client.send(TO, SUBJECT, BODY);
    try {
      final long started = System.nanoTime();
      assertThatThrownBy(() -> client.send(TO, SUBJECT, BODY))
          .isInstanceOf(ApiCallTimeoutException.class);
      // 応答を止める 10 秒より十分に短いことを確かめる。負荷の下でも通るよう、2 秒に 3 倍の余裕を見る。
      assertThat(Duration.ofNanos(System.nanoTime() - started))
          .as("タイムアウトするまでの時間")
          .isLessThan(Duration.ofSeconds(6));
    } finally {
      release.countDown();
    }
  }

  @Test
  @DisplayName("From の表示名が 1 つの encoded-word に収まらなければ、Client を作れない")
  void tooLongFromNameIsRejected() {
    // UTF-8 で 46 バイト。Base64 で 64 文字になり、前後の 12 文字と合わせて 75 文字を超える。
    final String fromName = "あ".repeat(15) + "a";
    try (SesV2Client unused = SesV2Client.builder().region(Region.AP_NORTHEAST_1).build()) {
      assertThatThrownBy(
              () -> new MailSenderClient(unused, FROM_ADDRESS, fromName, CONFIGURATION_SET))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("mail-sender.from-name");
    }
  }

  @Test
  @DisplayName("From の表示名が UTF-8 で 45 バイトなら、Client を作れる")
  void longestFromNameIsAccepted() {
    try (SesV2Client unused = SesV2Client.builder().region(Region.AP_NORTHEAST_1).build()) {
      assertThat(new MailSenderClient(unused, FROM_ADDRESS, "あ".repeat(15), CONFIGURATION_SET))
          .as("45 バイトの表示名の Client")
          .isNotNull();
    }
  }

  @Test
  @DisplayName("From のアドレスの設定がなければ、placeholder の解決の失敗で起動に失敗する")
  void missingFromAddressFailsStartup() {
    contextRunner()
        .withPropertyValues(
            "mail-sender.from-name=" + FROM_NAME,
            "mail-sender.configuration-set=" + CONFIGURATION_SET)
        .run(context ->
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                .hasMessageContaining("Could not resolve placeholder 'mail-sender.from-address'"));
  }

  @Test
  @DisplayName("設定セットの設定がなければ、placeholder の解決の失敗で起動に失敗する")
  void missingConfigurationSetFailsStartup() {
    contextRunner()
        .withPropertyValues(
            "mail-sender.from-address=" + FROM_ADDRESS, "mail-sender.from-name=" + FROM_NAME)
        .run(context ->
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                .hasMessageContaining(
                    "Could not resolve placeholder 'mail-sender.configuration-set'"));
  }

  /**
   * placeholder を解決して Client だけを登録する文脈。SDK の Client は接続しないので、リージョンだけを持たせる。
   *
   * <p>テストの JVM は {@code .env.test} の {@code MAIL_SENDER_*} を緩い束縛で読みうるため、環境変数の property source を外す。
   */
  private static ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withInitializer(context ->
            context
                .getEnvironment()
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
        .withBean(
            SesV2Client.class, () -> SesV2Client.builder().region(Region.AP_NORTHEAST_1).build())
        .withBean(MailSenderClient.class);
  }

  /** ループバックの空いているポートで HTTP サーバを起動し、その URL を接続先にした Client を返す。 */
  private MailSenderClient start(final HttpHandler handler) throws IOException {
    final HttpServer started = serve(handler);
    server = started;
    final SesV2Client client = sesV2Client(started, Duration.ofSeconds(2));
    sdkClient = client;
    return new MailSenderClient(client, FROM_ADDRESS, FROM_NAME, CONFIGURATION_SET);
  }

  /** ループバックの空いているポートで、SendEmail のパスを受ける HTTP サーバを起動する。 */
  private static HttpServer serve(final HttpHandler handler) throws IOException {
    final HttpServer started =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    started.createContext(SEND_EMAIL, handler);
    started.start();
    return started;
  }

  /** 本番と同じ設定の SDK の Client に、HTTP サーバの接続先、リージョン、資格情報を足して作る。 */
  private static SesV2Client sesV2Client(final HttpServer target, final Duration readTimeout) {
    return SesV2ClientConfig.build(
        SesV2Client.builder()
            .endpointOverride(
                URI.create(
                    "http://"
                        + InetAddress.getLoopbackAddress().getHostAddress()
                        + ":"
                        + target.getAddress().getPort()))
            .region(Region.AP_NORTHEAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test-access-key", "test-secret-key"))),
        Duration.ofSeconds(1),
        readTimeout);
  }

  /** JSON の本文で応答する。エラーの種類があれば、SES と同じく {@code x-amzn-ErrorType} のヘッダーで返す。本文が空なら本文を送らない。 */
  private static void reply(
      final HttpExchange exchange,
      final int status,
      final @Nullable String errorType,
      final String body)
      throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    if (errorType != null) {
      exchange.getResponseHeaders().add("x-amzn-ErrorType", errorType);
    }
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
