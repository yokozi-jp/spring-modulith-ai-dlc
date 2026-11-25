package com.example.demo;

import static com.example.demo.jooq.tenancy.Tables.M_TENANT;

import com.example.demo.product.TestProducts;
import com.example.demo.shared.persistence.TestCommonColumns;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.TenancySeeds;
import com.example.demo.testkit.AsModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E2E の各テストが自分専用の商品を作るための、ローカルかテストの DB だけに書く HTTP の入口（ADR-129）。
 *
 * <p>商品には作成の公開 API が無いため、Playwright の {@code productFactory} がこのサーバーへ {@code POST /products}
 * を送る。Spring を起動せず、JDK の {@link HttpServer} を {@code 127.0.0.1} にだけ開く。起動時にテナントと所属（{@link
 * TenancySeeds}）を入れてから listen するので、{@code GET /health} が応答すれば共有のデータが揃っている。
 *
 * <p>ブラウザの別のサイトからの要求を通さないよう、{@code Host} が loopback の名前と自分の port でない要求（DNS rebinding）は 403
 * にし、{@code POST /products} は preflight の要る {@code application/json} の本文だけを受ける。
 */
@Slf4j
@SuppressWarnings("PMD.DoNotUseThreads")
public final class E2eFixtureServer {

  /** 商品の行に書く {@code *_pgm_cd}。 */
  private static final String PGM_CD = "e2e.Fixture";

  /** 商品コードの接頭辞。商品コードの列（20 文字）に収まるよう、残りの 16 文字を UUID の 16 進で埋める。 */
  private static final String CODE_PREFIX = "E2E-";

  /** 商品コードの、接頭辞の後ろの 16 進の桁数。 */
  private static final int CODE_HEX_LENGTH = 16;

  /** 単価の上限。商品の単価の列（numeric(12, 2)）の整数部に収める。 */
  private static final long MAX_UNIT_PRICE = 9_999_999_999L;

  /** 要求の本文の上限のバイト数。商品の作成の本文は 100 バイトに満たない。 */
  private static final int MAX_BODY_BYTES = 8 * 1024;

  /** 受け付ける本文のメディアタイプ。 */
  private static final String JSON_MEDIA_TYPE = "application/json";

  /** 商品の登録日時。シーダーと同じ固定の時刻で、画面にも API にも出ない。 */
  private static final Instant REGISTERED_AT = Instant.parse("2026-10-01T00:00:00Z");

  /** 要求と応答の本文の JSON。 */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** 接続先を読む環境変数。 */
  private final Map<String, String> env;

  /** テナントの名前から ID への対応。起動時に {@code tenancy.m_tenant} から読む。 */
  private final Map<String, TenantId> tenants;

  /** 受け付ける {@code Host} の値（{@code 127.0.0.1:<port>} と {@code localhost:<port>}）。 */
  private final Set<String> hosts;

  private E2eFixtureServer(
      final Map<String, String> env, final Map<String, TenantId> tenants, final int port) {
    this.env = env;
    this.tenants = Map.copyOf(tenants);
    this.hosts = Set.of("127.0.0.1:" + port, "localhost:" + port);
  }

  /** テナントと所属を入れ、{@code E2E_FIXTURE_PORT} で listen する。ローカルかテストの環境でなければ DB に触れずに止まる。 */
  public static void main(final String[] args) throws IOException, SQLException {
    final Map<String, String> env = System.getenv();
    LocalDatabase.requireLocalEnvironment(env);
    final int port = Integer.parseInt(LocalDatabase.required(env, "E2E_FIXTURE_PORT"));
    final String issuer = LocalDatabase.required(env, LocalDatabase.OIDC_ISSUER_URI);
    final Map<String, TenantId> tenants;
    try (Connection connection = LocalDatabase.connect(env)) {
      tenants =
          AsModule.callWithoutTenant(
              LocalDatabase.dsl(connection),
              "tenancy",
              tx -> {
                TenancySeeds.insert(tx, issuer);
                return tx.select(M_TENANT.TENANT_NAME, M_TENANT.PUBLIC_ID)
                    .from(M_TENANT)
                    .fetchMap(M_TENANT.TENANT_NAME, row -> new TenantId(row.value2()));
              });
    }
    start(env, tenants, port);
    final Set<String> names = tenants.keySet();
    log.info("E2E の fixture サーバーを起動しました: http://127.0.0.1:{}（テナント {}）", port, names);
  }

  /** {@code 127.0.0.1} の port（0 なら空いている port）で listen を始める。DB には要求を受けるまで接続しない。 */
  /* package */ static HttpServer start(
      final Map<String, String> env, final Map<String, TenantId> tenants, final int port)
      throws IOException {
    final HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    final E2eFixtureServer fixture =
        new E2eFixtureServer(env, tenants, server.getAddress().getPort());
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", fixture::handle);
    server.start();
    return server;
  }

  // 応答せずに接続を切ると、Playwright には原因が見えない。DB の例外も補助の IllegalStateException も、ログに出して 500 で返す。
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void handle(final HttpExchange exchange) throws IOException {
    try (exchange) {
      final String route =
          exchange.getRequestMethod().toUpperCase(Locale.ROOT)
              + " "
              + exchange.getRequestURI().getPath();
      try {
        requireLoopbackHost(exchange);
        switch (route) {
          case "GET /health" -> respond(exchange, 200, "ok");
          case "POST /products" -> createProduct(exchange);
          default -> respond(exchange, 404, "見つかりません: " + route);
        }
      } catch (RejectedRequestException e) {
        respond(exchange, e.status, e.getMessage());
      } catch (SQLException | RuntimeException e) {
        log.error("fixture の要求に失敗しました: {}", route, e);
        respond(exchange, 500, e.getClass().getSimpleName());
      }
    }
  }

  private void requireLoopbackHost(final HttpExchange exchange) {
    final String host = exchange.getRequestHeaders().getFirst("Host");
    if (host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))) {
      throw new RejectedRequestException(403, "Host は次のどれかにしてください: " + hosts);
    }
  }

  private void createProduct(final HttpExchange exchange) throws IOException, SQLException {
    final ProductRequest request = parse(exchange);
    final UUID productId = UUID.randomUUID();
    final String productCode =
        CODE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, CODE_HEX_LENGTH);
    final String productName = "E2E 商品 " + productCode;
    try (Connection connection = LocalDatabase.connect(env)) {
      AsModule.run(
          LocalDatabase.dsl(connection),
          "product",
          request.tenant(),
          tx ->
              TestCommonColumns.runAs(
                  PGM_CD,
                  () ->
                      TestProducts.insert(
                          tx,
                          productId,
                          productCode,
                          productName,
                          BigDecimal.valueOf(request.unitPrice()).setScale(2),
                          request.onSale() ? "ON_SALE" : "DISCONTINUED",
                          REGISTERED_AT)));
    }
    final byte[] body =
        JSON.writeValueAsBytes(
            new CreatedProduct(
                productId, productCode, productName, request.unitPrice(), request.onSale()));
    exchange.getResponseHeaders().set("Content-Type", JSON_MEDIA_TYPE);
    exchange.sendResponseHeaders(201, body.length);
    exchange.getResponseBody().write(body);
  }

  private ProductRequest parse(final HttpExchange exchange) throws IOException {
    final String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType == null
        || !JSON_MEDIA_TYPE.equalsIgnoreCase(contentType.split(";", 2)[0].strip())) {
      throw new RejectedRequestException(415, "Content-Type は " + JSON_MEDIA_TYPE + " にしてください");
    }
    final byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
    if (bytes.length > MAX_BODY_BYTES) {
      throw new RejectedRequestException(413, "本文は " + MAX_BODY_BYTES + " バイト以下にしてください");
    }
    final JsonNode body;
    try {
      body = JSON.readTree(bytes);
    } catch (JacksonException e) {
      throw new RejectedRequestException(400, "本文が JSON ではありません", e);
    }
    return new ProductRequest(
        tenantOf(body.path("tenant")),
        onSaleOf(body.path("onSale")),
        unitPriceOf(body.path("unitPrice")));
  }

  private TenantId tenantOf(final JsonNode name) {
    final TenantId tenant = name.isString() ? tenants.get(name.stringValue()) : null;
    if (tenant == null) {
      throw new RejectedRequestException(400, "tenant は次のテナントの名前にしてください: " + tenants.keySet());
    }
    return tenant;
  }

  private static boolean onSaleOf(final JsonNode onSale) {
    if (!onSale.isBoolean()) {
      throw new RejectedRequestException(400, "onSale は true か false にしてください");
    }
    return onSale.booleanValue();
  }

  private static long unitPriceOf(final JsonNode unitPrice) {
    if (!unitPrice.isIntegralNumber()
        || !unitPrice.canConvertToLong()
        || unitPrice.longValue() < 1
        || unitPrice.longValue() > MAX_UNIT_PRICE) {
      throw new RejectedRequestException(400, "unitPrice は 1 から " + MAX_UNIT_PRICE + " の整数にしてください");
    }
    return unitPrice.longValue();
  }

  private static void respond(final HttpExchange exchange, final int status, final String text)
      throws IOException {
    final byte[] body = text.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  /** 検査した商品の作成の要求。 */
  private record ProductRequest(TenantId tenant, boolean onSale, long unitPrice) {}

  /** 作った商品。 */
  private record CreatedProduct(
      UUID productId, String productCode, String productName, long unitPrice, boolean onSale) {}

  /** 要求が契約に合わない。{@link #status} で応答する。 */
  private static final class RejectedRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 応答の status。 */
    private final int status;

    /* package */ RejectedRequestException(final int status, final String message) {
      super(message);
      this.status = status;
    }

    /* package */ RejectedRequestException(
        final int status, final String message, final Throwable cause) {
      super(message, cause);
      this.status = status;
    }
  }
}
