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
 * <p>リージョンは {@code AWS_REGION} から読み、未設定なら起動に失敗する。本番は ECS がタスクに入れる。
 *
 * <p>接続先は、Spring の設定（{@code .env}、環境変数、テストの設定）に {@code AWS_ENDPOINT_URL_SESV2} があるときだけ上書きし、資格情報も
 * {@code AWS_ACCESS_KEY_ID} と {@code AWS_SECRET_ACCESS_KEY} の固定の値にする。ローカルとテストが WireMock
 * を指す経路である。ないときは、接続先と資格情報を SDK の既定の取得に任せ、本番は ECS のタスクのロールを使う。この分岐は設定の有無だけで決まり、送り方は変えない（ADR-125）。
 */
@Configuration(proxyBeanMethods = false)
class SesV2ClientConfig {

  /**
   * タイムアウトを持ち、再試行しない SES v2 の Client を作る。Spring が終了時に {@code close} を呼ぶ。
   *
   * <p>HTTP の Client は、依存を増やさない JDK の {@code HttpURLConnection} の実装にする。環境変数のプロキシ（{@code HTTP_PROXY}
   * など）は読まない。読むと、{@code NO_PROXY} に入っていない WireMock への通信がプロキシへ向かい、決済代行の Client の挙動ともそろわない。{@code
   * SendEmail} には冪等キーがないため、SDK の再試行を止める。
   *
   * @param connectTimeout 接続の確立を待つ上限
   * @param readTimeout 応答の読み取りと、一回の呼び出し全体を待つ上限
   * @param region SES のリージョン
   * @param endpoint SES v2 の API の接続先。空なら SDK の既定の取得に任せる
   * @param accessKeyId 接続先を上書きするときのアクセスキー
   * @param secretAccessKey 接続先を上書きするときのシークレットキー
   */
  @Bean
  /* package */ SesV2Client sesV2Client(
      @Value("${mail-sender.connect-timeout}") final Duration connectTimeout,
      @Value("${mail-sender.read-timeout}") final Duration readTimeout,
      @Value("${AWS_REGION}") final String region,
      @Value("${AWS_ENDPOINT_URL_SESV2:}") final String endpoint,
      @Value("${AWS_ACCESS_KEY_ID:}") final String accessKeyId,
      @Value("${AWS_SECRET_ACCESS_KEY:}") final String secretAccessKey) {
    final SesV2ClientBuilder builder = SesV2Client.builder().region(Region.of(region));
    if (!endpoint.isBlank()) {
      builder
          .endpointOverride(URI.create(endpoint))
          .credentialsProvider(
              StaticCredentialsProvider.create(
                  AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
    }
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
