package com.example.demo.tenancy.infrastructure.client;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.SesV2ClientBuilder;

/**
 * メールを送る SES v2 の SDK の Client を作る。
 *
 * <p>接続先、リージョン、資格情報は SDK の既定の取得に任せ、ここで設定しない（ADR-125）。本番は ECS のタスクのロールと {@code AWS_REGION}
 * を使い、ローカルとテストは {@code AWS_ENDPOINT_URL_SESV2} で WireMock を指す。リージョンが取れなければ Client を作れず、起動に失敗する。
 */
@Configuration(proxyBeanMethods = false)
class SesV2ClientConfig {

  /**
   * タイムアウトを持ち、再試行しない SES v2 の Client を作る。Spring が終了時に {@code close} を呼ぶ。
   *
   * @param connectTimeout 接続の確立を待つ上限
   * @param readTimeout 応答の読み取りと、一回の呼び出し全体を待つ上限
   */
  @Bean
  /* package */ SesV2Client sesV2Client(
      @Value("${mail-sender.connect-timeout}") final Duration connectTimeout,
      @Value("${mail-sender.read-timeout}") final Duration readTimeout) {
    return build(SesV2Client.builder(), connectTimeout, readTimeout);
  }

  /**
   * builder に、HTTP の Client のタイムアウト、呼び出し全体のタイムアウト、試行 1 回の再試行の方針を足して Client を作る。
   *
   * <p>HTTP の Client は、依存を増やさない JDK の {@code HttpURLConnection} の実装にする。{@code SendEmail}
   * には冪等キーがないため、SDK の再試行を止める。テストは接続先と資格情報を足した builder を渡す。
   */
  /* package */ static SesV2Client build(
      final SesV2ClientBuilder builder, final Duration connectTimeout, final Duration readTimeout) {
    return builder
        .httpClientBuilder(
            UrlConnectionHttpClient.builder()
                .connectionTimeout(connectTimeout)
                .socketTimeout(readTimeout))
        .overrideConfiguration(override ->
            override.apiCallTimeout(readTimeout).retryStrategy(AwsRetryStrategy.doNotRetry()))
        .build();
  }
}
