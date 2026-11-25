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
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * E2E の各テストが自分専用の商品を作るための、ローカルかテストの DB だけに書く HTTP の入口（ADR-129）。
 *
 * <p>商品には作成の公開 API が無いため、Playwright の {@code productFactory} がこのサーバーへ {@code POST /products}
 * を送る。Spring を起動せず、JDK の {@link HttpServer} を {@code 127.0.0.1} にだけ開く。起動時にテナントと所属（{@link
 * TenancySeeds}）を入れてから listen するので、{@code GET /health} が応答すれば共有のデータが揃っている。
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

  /** 商品の登録日時。シーダーと同じ固定の時刻で、画面にも API にも出ない。 */
  private static final Instant REGISTERED_AT = Instant.parse("2026-10-01T00:00:00Z");

  /** 実行の SQL をログに出さない jOOQ の設定。 */
  private static final Settings SETTINGS = new Settings().withExecuteLogging(false);

  /** 要求と応答の本文の JSON。 */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** 接続先を読む環境変数。 */
  private final Map<String, String> env;

  /** テナントの名前から ID への対応。起動時に {@code tenancy.m_tenant} から読む。 */
  private final Map<String, TenantId> tenants;

  /** 商品の登録日時を取る時計。 */
  private final Clock clock;

  /* package */ E2eFixtureServer(
      final Map<String, String> env, final Map<String, TenantId> tenants, final Clock clock) {
    this.env = env;
    this.tenants = Map.copyOf(tenants);
    this.clock = clock;
  }

  /** テナントと所属を入れ、{@code E2E_FIXTURE_PORT} で listen する。ローカルかテストの環境でなければ DB に触れずに止まる。 */
  public static void main(final String[] args) throws IOException, SQLException {
    final Map<String, String> env = System.getenv();
    LocalDatabase.requireLocalEnvironment(env);
    final int port = Integer.parseInt(LocalDatabase.required(env, "E2E_FIXTURE_PORT"));
    final String issuer = LocalDatabase.required(env, "OIDC_ISSUER_URI");
    final Map<String, TenantId> tenants;
    try (Connection connection = LocalDatabase.connect(env)) {
      tenants =
          AsModule.callWithoutTenant(
              dsl(connection),
              "tenancy",
              tx -> {
                TenancySeeds.insert(tx, issuer);
                return tx.select(M_TENANT.TENANT_NAME, M_TENANT.PUBLIC_ID)
                    .from(M_TENANT)
                    .fetchMap(M_TENANT.TENANT_NAME, row -> new TenantId(row.value2()));
              });
    }
    final E2eFixtureServer fixture =
        new E2eFixtureServer(env, tenants, Clock.fixed(REGISTERED_AT, ZoneOffset.UTC));
    final HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", fixture::handle);
    server.start();
    final Set<String> names = tenants.keySet();
    log.info("E2E の fixture サーバーを起動しました: http://127.0.0.1:{}（テナント {}）", port, names);
  }

  private void handle(final HttpExchange exchange) throws IOException {
    try (exchange) {
      final String route =
          exchange.getRequestMethod().toUpperCase(Locale.ROOT)
              + " "
              + exchange.getRequestURI().getPath();
      try {
        switch (route) {
          case "GET /health" -> respond(exchange, 200, "ok");
          case "POST /products" -> createProduct(exchange);
          default -> respond(exchange, 404, "見つかりません: " + route);
        }
      } catch (InvalidRequestException e) {
        respond(exchange, 400, e.getMessage());
      } catch (DataAccessException | SQLException e) {
        log.error("fixture の要求に失敗しました: {}", route, e);
        respond(exchange, 500, e.getClass().getSimpleName());
      }
    }
  }

  private void createProduct(final HttpExchange exchange) throws IOException, SQLException {
    final ProductRequest request = parse(exchange.getRequestBody());
    final UUID productId = UUID.randomUUID();
    final String productCode =
        CODE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, CODE_HEX_LENGTH);
    final String productName = "E2E 商品 " + productCode;
    try (Connection connection = LocalDatabase.connect(env)) {
      AsModule.run(
          dsl(connection),
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
                          Instant.now(clock))));
    }
    final byte[] body =
        JSON.writeValueAsBytes(
            new CreatedProduct(
                productId, productCode, productName, request.unitPrice(), request.onSale()));
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(201, body.length);
    exchange.getResponseBody().write(body);
  }

  private ProductRequest parse(final InputStream input) throws IOException {
    final JsonNode body;
    try {
      body = JSON.readTree(input.readAllBytes());
    } catch (JacksonException e) {
      throw new InvalidRequestException("本文が JSON ではありません", e);
    }
    return new ProductRequest(
        tenantOf(body.path("tenant")),
        onSaleOf(body.path("onSale")),
        unitPriceOf(body.path("unitPrice")));
  }

  private TenantId tenantOf(final JsonNode name) {
    final TenantId tenant = name.isString() ? tenants.get(name.stringValue()) : null;
    if (tenant == null) {
      throw new InvalidRequestException("tenant は次のテナントの名前にしてください: " + tenants.keySet());
    }
    return tenant;
  }

  private static boolean onSaleOf(final JsonNode onSale) {
    if (!onSale.isBoolean()) {
      throw new InvalidRequestException("onSale は true か false にしてください");
    }
    return onSale.booleanValue();
  }

  private static long unitPriceOf(final JsonNode unitPrice) {
    if (!unitPrice.isIntegralNumber()
        || !unitPrice.canConvertToLong()
        || unitPrice.longValue() < 1
        || unitPrice.longValue() > MAX_UNIT_PRICE) {
      throw new InvalidRequestException("unitPrice は 1 から " + MAX_UNIT_PRICE + " の整数にしてください");
    }
    return unitPrice.longValue();
  }

  private static DSLContext dsl(final Connection connection) {
    return DSL.using(connection, SQLDialect.POSTGRES, SETTINGS);
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
  /* package */ record CreatedProduct(
      UUID productId, String productCode, String productName, long unitPrice, boolean onSale) {}

  /** 要求の本文が契約に合わない。400 で応答する。 */
  private static final class InvalidRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /* package */ InvalidRequestException(final String message) {
      super(message);
    }

    /* package */ InvalidRequestException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }
}
