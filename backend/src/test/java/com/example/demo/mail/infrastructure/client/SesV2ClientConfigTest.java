package com.example.demo.mail.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.testkit.LoopbackHttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * メールを送る SES v2 の SDK の Client が、接続の上限に connectTimeout を、読み取りの上限に readTimeout を使うことを検証する。
 *
 * <p>呼び出し全体の上限は {@code MailSenderClientTest} が確かめる。ここでは要求ごとに呼び出し全体の上限を延ばし、確かめないほうの上限を長くして、
 * 取り違えると別の例外になるか、応答が先に届くようにする。
 */
class SesV2ClientConfigTest {

  /** 確かめるほうの上限。 */
  private static final Duration SHORT = Duration.ofSeconds(1);

  /** 確かめないほうの上限。呼び出し全体の上限と、応答を止める時間より長い。 */
  private static final Duration LONG = Duration.ofSeconds(20);

  @Test
  @DisplayName("接続を受け付けない相手には、connectTimeout の上限で SdkClientException を投げる")
  void connectionTimesOut() throws IOException {
    // ponytail: Linux は backlog 1 の accept の待ち行列を 2 本の接続で埋め、その後の SYN を捨てるので、3 本目の接続が確立しない。
    // 待ち行列の大きさが違う OS では、2 本目の接続が 1 秒で失敗するか、3 本目が接続できてこのテストが失敗する。
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket _ = connectTo(listener);
        Socket _ = connectTo(listener);
        SesV2Client client =
            client(
                URI.create(
                    "http://"
                        + listener.getInetAddress().getHostAddress()
                        + ":"
                        + listener.getLocalPort()),
                SHORT,
                LONG)) {
      assertThatThrownBy(() -> send(client, Duration.ofSeconds(10)))
          .isInstanceOf(SdkClientException.class)
          .rootCause()
          .isInstanceOf(SocketTimeoutException.class)
          .hasMessageContaining("Connect timed out");
    }
  }

  @Test
  @DisplayName("応答を返さない相手には、readTimeout の上限で SdkClientException を投げる")
  // 応答を止める間に割り込まれたら、割り込みの状態を戻す。
  @SuppressWarnings("PMD.DoNotUseThreads")
  void readTimesOut() throws IOException {
    final CountDownLatch release = new CountDownLatch(1);
    final LoopbackHttpServer server =
        LoopbackHttpServer.start()
            .handle(
                "/",
                exchange -> {
                  try {
                    release.await(10, TimeUnit.SECONDS);
                  } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                  }
                  LoopbackHttpServer.reply(exchange, 200, "{\"MessageId\":\"msg-1\"}");
                });
    try (SesV2Client client = client(URI.create(server.url()), LONG, SHORT)) {
      assertThatThrownBy(() -> send(client, Duration.ofSeconds(30)))
          .isInstanceOf(SdkClientException.class)
          .rootCause()
          .isInstanceOf(SocketTimeoutException.class)
          .hasMessageContaining("Read timed out");
    } finally {
      release.countDown();
      server.stop();
    }
  }

  /** 上限を渡して作った SDK の Client に、接続先、リージョン、資格情報を足して作る。 */
  private static SesV2Client client(
      final URI endpoint, final Duration connectTimeout, final Duration readTimeout) {
    return SesV2ClientConfig.build(
        SesV2Client.builder()
            .endpointOverride(endpoint)
            .region(Region.AP_NORTHEAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test-access-key", "test-secret-key"))),
        connectTimeout,
        readTimeout);
  }

  /** 呼び出し全体の上限を apiCallTimeout に延ばして 1 通送る。 */
  private static void send(final SesV2Client client, final Duration apiCallTimeout) {
    client.sendEmail(request ->
        request
            .fromEmailAddress("no-reply@example.com")
            .destination(destination -> destination.toAddresses("invitee@example.com"))
            .content(content ->
                content.simple(message ->
                    message
                        .subject(subject -> subject.data("subject"))
                        .body(body -> body.text(text -> text.data("body")))))
            .overrideConfiguration(override -> override.apiCallTimeout(apiCallTimeout)));
  }

  /** listener へ接続する。待ち行列が埋まっていて接続できなければ、1 秒で失敗する。 */
  private static Socket connectTo(final ServerSocket listener) throws IOException {
    final Socket socket = new Socket();
    socket.connect(listener.getLocalSocketAddress(), 1000);
    return socket;
  }
}
