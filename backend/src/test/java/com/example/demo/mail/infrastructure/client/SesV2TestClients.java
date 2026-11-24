package com.example.demo.mail.infrastructure.client;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/** テストが使う、本番と同じ設定の SES v2 の SDK の Client を作る。 */
final class SesV2TestClients {

  private SesV2TestClients() {}

  /** 本番と同じ設定の SDK の Client に、接続先、リージョン、ダミーの資格情報を足して作る。 */
  /* package */ static SesV2Client create(
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
}
