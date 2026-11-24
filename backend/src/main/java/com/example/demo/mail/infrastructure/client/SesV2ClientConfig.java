package com.example.demo.mail.infrastructure.client;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.SesV2ClientBuilder;

/**
 * メールを送る SES v2 の SDK の Client を作る。
 *
 * <p>{@code AWS_ENDPOINT_URL_SESV2} が Spring の設定（{@code .env}、環境変数、テストの設定）にあるときだけ、その接続先、{@code
 * AWS_REGION}、 {@code AWS_ACCESS_KEY_ID}、{@code AWS_SECRET_ACCESS_KEY} を Client に渡す。ローカルとテストが
 * WireMock を指す経路である。ない ときは SDK の既定の取得に任せる。本番は ECS のタスクのロールと {@code AWS_REGION} を使う。リージョンが取れなければ
 * Client を作れず、起動に失敗する（ADR-125）。
 */
@Configuration(proxyBeanMethods = false)
class SesV2ClientConfig {

  /**
   * タイムアウトを持ち、再試行しない SES v2 の Client を作る。Spring が終了時に {@code close} を呼ぶ。
   *
   * @param connectTimeout 接続の確立を待つ上限
   * @param readTimeout 応答の読み取りと、一回の呼び出し全体を待つ上限
   * @param endpoint SES v2 の API の接続先。空なら SDK の既定の取得に任せる
   * @param region 接続先を渡すときのリージョン
   * @param accessKeyId 接続先を渡すときのアクセスキー
   * @param secretAccessKey 接続先を渡すときのシークレットキー
   */
  @Bean
  /* package */ SesV2Client sesV2Client(
      @Value("${mail-sender.connect-timeout}") final Duration connectTimeout,
      @Value("${mail-sender.read-timeout}") final Duration readTimeout,
      @Value("${AWS_ENDPOINT_URL_SESV2:}") final String endpoint,
      @Value("${AWS_REGION:}") final String region,
      @Value("${AWS_ACCESS_KEY_ID:}") final String accessKeyId,
      @Value("${AWS_SECRET_ACCESS_KEY:}") final String secretAccessKey) {
    final SesV2ClientBuilder builder = SesV2Client.builder();
    if (!endpoint.isBlank()) {
      builder
          .endpointOverride(URI.create(endpoint))
          .region(Region.of(region))
          .credentialsProvider(
              StaticCredentialsProvider.create(
                  AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
    }
    return build(builder, connectTimeout, readTimeout);
  }

  /**
   * builder に、HTTP の Client のタイムアウト、呼び出し全体のタイムアウト、試行 1 回の再試行の方針を足して Client を作る。
   *
   * <p>HTTP の Client は、依存を増やさない JDK の {@code HttpURLConnection} の実装にする。環境変数のプロキシ（{@code HTTP_PROXY}
   * など）は読まない。読むと、{@code NO_PROXY} に入っていない WireMock への通信がプロキシへ向かい、決済代行の Client の挙動ともそろわない。{@code
   * SendEmail} には冪等キーがないため、SDK の再試行を止める。テストは接続先と資格情報を足した builder を渡す。
   */
  /* package */ static SesV2Client build(
      final SesV2ClientBuilder builder, final Duration connectTimeout, final Duration readTimeout) {
    return builder
        .httpClientBuilder(
            UrlConnectionHttpClient.builder()
                .connectionTimeout(connectTimeout)
                .socketTimeout(readTimeout)
                .proxyConfiguration(proxy -> proxy.useEnvironmentVariablesValues(false)))
        .overrideConfiguration(override ->
            override.apiCallTimeout(readTimeout).retryStrategy(AwsRetryStrategy.doNotRetry()))
        .build();
  }
}
