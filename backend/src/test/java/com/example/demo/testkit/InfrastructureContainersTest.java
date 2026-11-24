package com.example.demo.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * Spring の設定の接続先が、{@link InfrastructureContainers} の起動したコンテナを指すことを確かめる（ADR-085）。
 *
 * <p>{@code application.yaml} のプレースホルダの名前が変わると、接続先が {@code .env.test} の値に戻り、fork どうしで DB
 * を共有してしまう。その変化をここで検出する。
 */
@SpringBootTest
@Import(SharedTestConfiguration.class)
class InfrastructureContainersTest {

  /** テストの設定。 */
  @Autowired private Environment environment;

  /** アプリケーションの接続の pool。 */
  @Autowired private DataSource dataSource;

  /** メールを送る SDK の Client。 */
  @Autowired private SesV2Client sesV2Client;

  @Test
  @DisplayName("DB、決済代行、SES の接続先は、この JVM が起動したコンテナを指す")
  void connectionsPointToStartedContainers() throws SQLException {
    final Map<String, Object> started = InfrastructureContainers.properties();
    final String jdbcUrl =
        "jdbc:postgresql://" + started.get("DB_HOST") + ":" + started.get("DB_PORT") + "/demo";

    assertThat(environment.getProperty("spring.datasource.url"))
        .as("spring.datasource.url")
        .isEqualTo(jdbcUrl);
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getURL()).as("接続した DB").isEqualTo(jdbcUrl);
    }
    assertThat(environment.getProperty("payment-gateway.base-url"))
        .as("payment-gateway.base-url")
        .isEqualTo(started.get("PAYMENT_GATEWAY_BASE_URL"));
    // SDK はシステムプロパティから接続先を読み、Client の設定の endpointOverride に入れる（ADR-125）。
    assertThat(sesV2Client.serviceClientConfiguration().endpointOverride())
        .as("SES v2 の Client の接続先")
        .hasValue(URI.create((String) started.get("AWS_ENDPOINT_URL_SESV2")));
  }
}
