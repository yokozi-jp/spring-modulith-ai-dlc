package com.example.demo.mail.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.mail.domain.model.MailBody;
import com.example.demo.mail.domain.model.MailOutcome;
import com.example.demo.mail.domain.model.MailRecipient;
import com.example.demo.mail.domain.model.MailSender;
import com.example.demo.mail.domain.model.MailSubject;
import com.example.demo.testkit.SharedTestConfiguration;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * メールの送信の Client を、テストの JVM が起動した WireMock のコンテナ（InfrastructureContainers）に向けて、送ったメールの中身と circuit
 * breaker の数え方を検証する。
 *
 * <p>Spring の Client は、本番と同じく SDK の既定の取得で接続先を読み、テストではシステムプロパティ {@code aws.endpointUrlSesV2} が
 * WireMock を指す（ADR-125）。成功は compose の WireMock と同じ {@code docker/wiremock/mappings}
 * の共有のスタブで返し、Client とスタブの契約が合うことを確かめる。失敗は、宛先に合う優先度の高いスタブをテストの中で足す。送ったメールは、E2E と同じく WireMock
 * の要求の記録（{@code POST /__admin/requests/find}）から宛先で絞って読む。結果の分け方は {@code MailSenderClientTest} が確かめる。
 */
@SpringBootTest
@Import(SharedTestConfiguration.class)
class MailSenderClientIntegrationTest {

  /** SES v2 の SendEmail のパス。 */
  private static final String SEND_EMAIL = "/v2/email/outbound-emails";

  /** 宛先を読む JsonPath。 */
  private static final String TO_ADDRESS = "$.Destination.ToAddresses[0]";

  /** SES がエラーの種類を返すヘッダー。 */
  private static final String ERROR_TYPE = "x-amzn-ErrorType";

  /** 件名。 */
  private static final MailSubject SUBJECT = new MailSubject("デモアプリ 招待のお知らせ");

  /** 本文。リンクを含む。 */
  private static final MailBody BODY =
      new MailBody("次のリンクから参加してください。\nhttps://app.example.com/invitations/1");

  /** 要求の本文を読む。 */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Resilience4j の aspect を通るメールの送信の Client。 */
  @Autowired private MailSender mailSender;

  /** Spring が作った SDK の Client。接続先を読む。 */
  @Autowired private SesV2Client sesV2Client;

  /** メールの送信の circuit breaker を読む。 */
  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  /** テストの JVM の WireMock の管理 API。 */
  private WireMock wireMock;

  /** メールの送信の circuit breaker。 */
  private CircuitBreaker circuitBreaker;

  /** テストごとの宛先。共有の WireMock の記録をほかのテストの要求と分ける。 */
  private MailRecipient to;

  /** テストが足したスタブ。 */
  private final List<StubMapping> stubs = new ArrayList<>();

  /**
   * SDK のクラスを、長いタイムアウトの別の Client で 1 回呼んで読み込んでおく。
   *
   * <p>SDK の最初の呼び出しは負荷の下で呼び出しのタイムアウト 2 秒を超えうるため、Spring の Client の最初の呼び出しをテストにしない。 引数の Client を受けて
   * Spring のコンテキストを先に作り、SDK の接続先のシステムプロパティを設定させる。
   */
  @BeforeAll
  static void warmUpSdk(@Autowired final SesV2Client springClient) {
    try (SesV2Client client =
        SesV2ClientConfig.build(
            SesV2Client.builder(), Duration.ofSeconds(1), Duration.ofSeconds(30))) {
      new MailSenderClient(client, "warm-up@example.com", "warm-up", "warm-up")
          .send(new MailRecipient("warm-up@example.com"), SUBJECT, BODY);
    }
  }

  /** WireMock の管理 API を SDK の接続先に向け、宛先を新しくし、circuit breaker を閉じて件数を消す。 */
  @BeforeEach
  void setUp() {
    final URI endpoint =
        sesV2Client
            .serviceClientConfiguration()
            .endpointOverride()
            .orElseThrow(() -> new AssertionError("SES v2 の Client の接続先がない"));
    wireMock = new WireMock(endpoint.getHost(), endpoint.getPort());
    to = new MailRecipient("invitee-" + UUID.randomUUID() + "@example.com");
    circuitBreaker = circuitBreakerRegistry.circuitBreaker("mail-sender");
    circuitBreaker.reset();
  }

  /** テストが足したスタブを取り除き、共有のスタブだけに戻す。 */
  @AfterEach
  void removeStubs() {
    stubs.forEach(wireMock::removeStubMapping);
    stubs.clear();
  }

  @Test
  @DisplayName("共有のスタブで送れ、送ったメールの宛先、件名、本文、From、設定セットを WireMock の要求の記録から読める")
  void sentMailIsReadableFromRequestJournal() {
    final MailOutcome outcome = mailSender.send(to, SUBJECT, BODY);

    assertThat(outcome)
        .as("送信の結果")
        .isInstanceOfSatisfying(
            MailOutcome.Sent.class,
            sent -> assertThat(sent.messageId().value()).as("MessageId").isNotBlank());
    final List<LoggedRequest> requests = sentTo(to);
    assertThat(requests).as("宛先への SendEmail の要求").hasSize(1);
    final JsonNode mail = JSON.readTree(requests.getFirst().getBodyAsString());
    assertThat(mail.at("/Destination/ToAddresses/0").asString()).as("宛先").isEqualTo(to.value());
    assertThat(mail.at("/Content/Simple/Subject/Data").asString())
        .as("件名")
        .isEqualTo(SUBJECT.value());
    assertThat(mail.at("/Content/Simple/Body/Text/Data").asString())
        .as("本文")
        .isEqualTo(BODY.value());
    // From と設定セットは .env.test.example の MAIL_SENDER_* の値。表示名「DemoApp」の encoded-word。
    assertThat(mail.at("/FromEmailAddress").asString())
        .as("From")
        .isEqualTo("=?UTF-8?B?RGVtb0FwcA==?= <no-reply@example.com>");
    assertThat(mail.at("/ConfigurationSetName").asString()).as("設定セット").isEqualTo("test");
  }

  /** circuit breaker が数える失敗と数えない失敗の応答。 */
  private static Stream<Arguments> failures() {
    return Stream.of(
        Arguments.of(
            "スロットリングの 429 は数える",
            WireMock.aResponse()
                .withStatus(429)
                .withHeader(ERROR_TYPE, "TooManyRequestsException")
                .withBody("{\"message\":\"Maximum sending rate exceeded.\"}"),
            TooManyRequestsException.class,
            1),
        Arguments.of(
            "接続の切断は数える",
            WireMock.aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER),
            SdkClientException.class,
            1),
        Arguments.of(
            "権限の不備の 403 は数えない",
            WireMock.forbidden()
                .withHeader(ERROR_TYPE, "AccessDeniedException")
                .withBody("{\"Message\":\"User is not authorized to perform: ses:SendEmail\"}"),
            SesV2Exception.class,
            0));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("failures")
  @DisplayName("失敗は 1 回だけ呼んで例外を投げ、circuit breaker は application.yaml の述語のとおりに失敗を数える")
  void failureIsCountedByPredicate(
      final String name,
      final ResponseDefinitionBuilder response,
      final Class<? extends Throwable> thrown,
      final int failedCalls) {
    stub(to, response);

    assertThatThrownBy(() -> mailSender.send(to, SUBJECT, BODY)).as(name).isInstanceOf(thrown);

    assertThat(sentTo(to)).as("%s の要求", name).hasSize(1);
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls())
        .as("%s で circuit breaker が数えた失敗", name)
        .isEqualTo(failedCalls);
  }

  /** 宛先に合う SendEmail の要求にだけ、共有のスタブより優先して返す応答を足す。 */
  private void stub(final MailRecipient recipient, final ResponseDefinitionBuilder response) {
    stubs.add(
        wireMock.register(
            WireMock.post(WireMock.urlEqualTo(SEND_EMAIL))
                .atPriority(1)
                .withRequestBody(
                    WireMock.matchingJsonPath(TO_ADDRESS, WireMock.equalTo(recipient.value())))
                .willReturn(response.withHeader("Content-Type", "application/json"))));
  }

  /** 宛先に送った SendEmail の要求を、WireMock の要求の記録から読む。 */
  private List<LoggedRequest> sentTo(final MailRecipient recipient) {
    final RequestPatternBuilder pattern =
        WireMock.postRequestedFor(WireMock.urlEqualTo(SEND_EMAIL))
            .withRequestBody(
                WireMock.matchingJsonPath(TO_ADDRESS, WireMock.equalTo(recipient.value())));
    return wireMock.find(pattern);
  }
}
