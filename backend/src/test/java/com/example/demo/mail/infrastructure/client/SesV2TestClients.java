package com.example.demo.mail.infrastructure.client;

import java.time.Duration;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/** テストが、本番と同じ {@link SesV2ClientConfig} で、接続先とダミーの資格情報を渡した SES v2 の Client を作る。 */
final class SesV2TestClients {

  private SesV2TestClients() {}

  /** 本番と同じ Bean のメソッドに、接続先、リージョン、ダミーの資格情報を渡して作る。 */
  /* package */ static SesV2Client create(
      final String endpoint, final Duration connectTimeout, final Duration readTimeout) {
    return new SesV2ClientConfig()
        .sesV2Client(
            connectTimeout,
            readTimeout,
            "ap-northeast-1",
            endpoint,
            "test-access-key",
            "test-secret-key");
  }
}
