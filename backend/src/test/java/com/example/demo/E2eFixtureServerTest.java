package com.example.demo;

import static com.example.demo.jooq.product.Tables.M_PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.jooq.product.tables.records.MProductRecord;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.DatabaseTest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** E2E の fixture サーバーの HTTP の契約（docs/e2e/testing-strategy.md の「テストデータ」）を確かめる。 */
@DatabaseTest
// 契約の規則ごとに 1 つのテストを置くため、メソッド数の上限を外す。
@SuppressWarnings("PMD.TooManyMethods")
class E2eFixtureServerTest {

  /** 要求の本文に書くテナントの名前。 */
  private static final String TENANT_NAME = "テストのテナント";

  /** サーバーが知るテナント。テストの利用者のテナントにし、作った商品を読み戻せるようにする。 */
  private static final Map<String, TenantId> TENANTS = Map.of(TENANT_NAME, AsTenant.TENANT);

  /** 本文の、テナントまでの前半。 */
  private static final String TENANT_FIELD = "{\"tenant\":\"" + TENANT_NAME + "\",";

  /** 正しい本文。 */
  private static final String VALID_BODY = TENANT_FIELD + "\"onSale\":true,\"unitPrice\":1200}";

  /** 要求を送る client。 */
  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  /** 応答の JSON を読む。 */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** テスト用の DB の jOOQ のコンテキスト。テストごとにロールバックする。 */
  @Autowired private DSLContext dsl;

  /** テスト用の DB の接続先。 */
  @Autowired private Environment environment;

  /** テストが起動したサーバー。 */
  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("GET /health は 200 を返す")
  void health() throws IOException, InterruptedException {
    final HttpResponse<String> response = send(Map.of(), "GET", "/health", null, null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("ok");
  }

  @Test
  @DisplayName("知らないパスは 404 を返す")
  void unknownPath() throws IOException, InterruptedException {
    assertThat(send(Map.of(), "GET", "/products", null, null).statusCode()).isEqualTo(404);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        TENANT_FIELD + "\"unitPrice\":1200}",
        TENANT_FIELD + "\"onSale\":\"true\",\"unitPrice\":1200}",
        TENANT_FIELD + "\"onSale\":true}",
        TENANT_FIELD + "\"onSale\":true,\"unitPrice\":0}",
        TENANT_FIELD + "\"onSale\":true,\"unitPrice\":1.5}",
        TENANT_FIELD + "\"onSale\":true,\"unitPrice\":\"1200\"}",
        TENANT_FIELD + "\"onSale\":true,\"unitPrice\":10000000000}",
      })
  @DisplayName("契約に合わない本文は 400 を返す")
  void invalidBody(final String body) throws IOException, InterruptedException {
    assertThat(postProduct(Map.of(), body).statusCode()).isEqualTo(400);
  }

  @Test
  @DisplayName("知らないテナントの名前は 400 を返し、知っているテナントの名前を示す")
  void unknownTenant() throws IOException, InterruptedException {
    final HttpResponse<String> response =
        postProduct(Map.of(), VALID_BODY.replace(TENANT_NAME, "知らないテナント"));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains(TENANT_NAME);
  }

  @Test
  @DisplayName("POST /products は、Content-Type が application/json でなければ 415 を返す")
  void requiresJsonContentType() throws IOException, InterruptedException {
    final HttpResponse<String> response =
        send(Map.of(), "POST", "/products", "text/plain", VALID_BODY);

    assertThat(response.statusCode()).isEqualTo(415);
  }

  @Test
  @DisplayName("POST /products は、本文が 8 KiB を超えると 413 を返す")
  void rejectsLargeBody() throws IOException, InterruptedException {
    final String body = "{\"pad\":\"" + "x".repeat(8 * 1024) + "\"}";

    assertThat(postProduct(Map.of(), body).statusCode()).isEqualTo(413);
  }

  @Test
  @DisplayName("Host が 127.0.0.1 か localhost と自分の port でなければ 403 を返す")
  void rejectsForeignHost() throws IOException {
    server = E2eFixtureServer.start(Map.of(), TENANTS, 0);
    final InetSocketAddress address = server.getAddress();

    try (Socket socket = new Socket(address.getAddress(), address.getPort())) {
      socket
          .getOutputStream()
          .write(
              ("GET /health HTTP/1.1\r\nHost: rebind.example:"
                      + address.getPort()
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      final String response =
          new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

      assertThat(response).startsWith("HTTP/1.1 403 ");
    }
  }

  @Test
  @DisplayName("DB に接続できないなど想定外の失敗は、接続を切らずに 500 を返す")
  void unexpectedFailure() throws IOException, InterruptedException {
    assertThat(postProduct(Map.of(), VALID_BODY).statusCode()).isEqualTo(500);
  }

  @Test
  @DisplayName("POST /products は、テナントに商品をコミットし、201 で商品 ID、商品コード、商品名、単価、販売の状態を返す")
  void createsProduct() throws IOException, InterruptedException, SQLException {
    final Map<String, String> env = databaseEnv();
    final HttpResponse<String> response =
        postProduct(env, TENANT_FIELD + "\"onSale\":false,\"unitPrice\":9999999999}");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    final JsonNode created = JSON.readTree(response.body());
    final UUID productId = UUID.fromString(created.path("productId").stringValue());
    try {
      final MProductRecord row =
          AsModule.call(
              dsl,
              "product",
              AsTenant.TENANT,
              tx -> tx.selectFrom(M_PRODUCT).where(M_PRODUCT.PUBLIC_ID.eq(productId)).fetchOne());
      assertThat(row).as("productId=%s の商品の行", productId).isNotNull();
      assertThat(created.path("productCode").stringValue())
          .matches("E2E-[0-9a-f]{16}")
          .isEqualTo(row.getProductCode());
      assertThat(created.path("productName").stringValue())
          .contains(row.getProductCode())
          .isEqualTo(row.getProductName());
      assertThat(created.path("unitPrice").longValue()).isEqualTo(9_999_999_999L);
      assertThat(row.getUnitPriceJpy()).isEqualByComparingTo(new BigDecimal("9999999999"));
      assertThat(created.path("onSale").booleanValue()).isFalse();
      assertThat(row.getSalesStatusTyp()).isEqualTo("DISCONTINUED");
      assertThat(row.getCreatedPgmCd()).isEqualTo("e2e.Fixture");
    } finally {
      try (Connection connection = LocalDatabase.connect(env)) {
        AsModule.run(
            LocalDatabase.dsl(connection),
            "product",
            AsTenant.TENANT,
            tx -> tx.deleteFrom(M_PRODUCT).where(M_PRODUCT.PUBLIC_ID.eq(productId)).execute());
      }
    }
  }

  private Map<String, String> databaseEnv() {
    return Stream.of("DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD")
        .collect(Collectors.toMap(name -> name, environment::getRequiredProperty));
  }

  private HttpResponse<String> postProduct(final Map<String, String> env, final String body)
      throws IOException, InterruptedException {
    return send(env, "POST", "/products", "application/json", body);
  }

  private HttpResponse<String> send(
      final Map<String, String> env,
      final String method,
      final String path,
      final String contentType,
      final String body)
      throws IOException, InterruptedException {
    server = E2eFixtureServer.start(env, TENANTS, 0);
    final HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (contentType != null) {
      request.header("Content-Type", contentType);
    }
    return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
