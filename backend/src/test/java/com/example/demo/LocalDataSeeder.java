package com.example.demo;

import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static com.example.demo.jooq.ordering.Tables.T_ORDER_LINE;
import static com.example.demo.jooq.payment.Tables.T_PAYMENT;
import static com.example.demo.jooq.product.Tables.M_PRODUCT;

import com.example.demo.ordering.domain.model.Money;
import com.example.demo.ordering.domain.model.Order;
import com.example.demo.ordering.domain.model.ProductId;
import com.example.demo.ordering.domain.model.ProductOffer;
import com.example.demo.ordering.infrastructure.persistence.OrderSeeds;
import com.example.demo.payment.infrastructure.persistence.PaymentSeeds;
import com.example.demo.product.ProductSeeds;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

/**
 * 開発用の代表データ（#114）を、業務データが空のローカルかテストの DB に入れる。
 *
 * <p>Gradle の {@code seedLocalData}（JavaExec、テストの runtime classpath）が {@link #main} を呼ぶ。Spring
 * を起動せず、Bean にもしないので、ほかのテストのコンテキストでは動かない。環境の確認、「空」の判定、reset の削除を 1 つのトランザクションで行う。
 */
@Slf4j
public final class LocalDataSeeder {

  /** 実行のモード。 */
  /* package */ enum Mode {
    /** 起動の前の自動投入。ローカルでない、接続できない、未適用のときは何もせず正常に終わる。 */
    AUTO,
    /** 明示の投入。「空」のときだけ入れる。 */
    SEED,
    /** 業務データを消してから入れ直す。 */
    RESET
  }

  /** {@link #seed} の結果。 */
  /* package */ enum Outcome {
    /** 代表データを入れた。 */
    SEEDED,
    /** 業務データがあるので何もしなかった。 */
    SKIPPED_NOT_EMPTY,
    /** マイグレーションが未適用なので何もしなかった。 */
    SKIPPED_NOT_MIGRATED
  }

  /** 業務データの表。子から順に並べ、reset はこの順に DELETE する。modulith と Liquibase の表は含めない。 */
  /* package */ static final List<Table<?>> BUSINESS_TABLES =
      List.of(T_PAYMENT, T_ORDER_LINE, T_ORDER, M_PRODUCT);

  /** Datafaker と UUID の乱数の seed。 */
  private static final long RANDOM_SEED = 114L;

  /** 日時の基準。実行日によらず同じ値にする。 */
  private static final Clock SEED_CLOCK =
      Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);

  /** 環境の目印の変数名。 */
  private static final String ENVIRONMENT = "OTEL_DEPLOYMENT_ENVIRONMENT_NAME";

  /** 接続先のホストの変数名。 */
  private static final String DB_HOST = "DB_HOST";

  /** loopback の名前。 */
  private static final String LOCALHOST = "localhost";

  /** ローカルかテストの環境の目印（OTEL_DEPLOYMENT_ENVIRONMENT_NAME）の値。 */
  private static final List<String> LOCAL_ENVIRONMENTS = List.of("local", "test");

  /** 実行の SQL をログに出さない jOOQ の設定。 */
  private static final Settings SETTINGS = new Settings().withExecuteLogging(false);

  /** 日時の基準を取る時計。 */
  private final Clock clock;

  /** 日時の基準を取る時計を受け取る。 */
  /* package */ LocalDataSeeder(final Clock clock) {
    this.clock = clock;
  }

  /** 呼ぶ側のトランザクションの中で、モードに従って代表データを入れる。 */
  /* package */ Outcome seed(final DSLContext dsl, final Mode mode) {
    final boolean migrated =
        BUSINESS_TABLES.stream()
            .allMatch(
                table ->
                    dsl.fetchValue("select to_regclass({0})::text", DSL.inline(dsl.render(table)))
                        != null);
    if (!migrated) {
      if (mode == Mode.AUTO) {
        log.warn("業務の表がないため、代表データを入れません。task be-migrate の後に実行してください");
        return Outcome.SKIPPED_NOT_MIGRATED;
      }
      throw new IllegalStateException("業務の表がありません。task be-migrate の後に実行してください");
    }
    if (mode == Mode.RESET) {
      BUSINESS_TABLES.forEach(table -> dsl.deleteFrom(table).execute());
    }
    final List<String> nonEmpty =
        BUSINESS_TABLES.stream().filter(dsl::fetchExists).map(LocalDataSeeder::name).toList();
    if (!nonEmpty.isEmpty()) {
      log.info("業務データがあるため、代表データを入れません: {}", nonEmpty);
      return Outcome.SKIPPED_NOT_EMPTY;
    }
    final Instant base = Instant.now(clock);
    final Random random = new Random(RANDOM_SEED);
    final Faker faker = new Faker(Locale.ENGLISH, random);
    final Supplier<UUID> ids = () -> uuidV4(random);
    // faker と ids は同じ random を引く。順（商品 P01〜P05、注文 C01〜C04、決済）を変えると値が変わる。
    final List<ProductOffer> offers =
        ProductSeeds.insert(dsl, faker, ids, base.minus(Duration.ofDays(1))).stream()
            .filter(ProductSeeds.Seeded::onSale)
            .map(
                s ->
                    new ProductOffer(
                        new ProductId(s.publicId()), new Money(s.unitPrice()), s.onSale()))
            .toList();
    final Order confirmed = OrderSeeds.insert(dsl, offers, ids, base);
    PaymentSeeds.insert(
        dsl,
        ids.get(),
        confirmed.id().value(),
        confirmed.total().amount(),
        confirmed.orderedAt().plus(Duration.ofMinutes(1)));
    return Outcome.SEEDED;
  }

  // ponytail: DB_HOST の loopback の判定は、SSH のポート転送で STG の DB を localhost に向けた場合を防げない。
  // その場合は目印の OTEL_DEPLOYMENT_ENVIRONMENT_NAME だけが防ぐ。防ぐ必要が出たら、接続後に DB
  // 側の目印（例：current_database()）も確かめる。
  /** 目印が local か test で、DB_HOST が loopback のときだけ true を返す。 */
  /* package */ static boolean isLocalEnvironment(final Map<String, String> env) {
    final String environment = env.get(ENVIRONMENT);
    final String host = env.get(DB_HOST);
    return environment != null
        && LOCAL_ENVIRONMENTS.contains(environment)
        && host != null
        && isLoopbackHost(host);
  }

  /** ローカルかテストの環境でなければ {@link IllegalStateException} を投げる。 */
  /* package */ static void requireLocalEnvironment(final Map<String, String> env) {
    if (!isLocalEnvironment(env)) {
      throw new IllegalStateException(
          "ローカルかテストの環境でないため実行しません: OTEL_DEPLOYMENT_ENVIRONMENT_NAME="
              + env.get(ENVIRONMENT)
              + ", DB_HOST="
              + env.get(DB_HOST));
    }
  }

  /** 引数のモード（auto、seed、reset）で代表データを入れる。異常のときは例外を投げ、終了コードを 1 にする。 */
  public static void main(final String[] args) throws SQLException {
    if (args.length == 0) {
      throw new IllegalArgumentException("引数にモード（auto、seed、reset）を渡してください");
    }
    final Mode mode = Mode.valueOf(args[0].toUpperCase(Locale.ROOT));
    final Map<String, String> env = System.getenv();
    if (mode == Mode.AUTO && !isLocalEnvironment(env)) {
      final String environment = env.get(ENVIRONMENT);
      final String host = env.get(DB_HOST);
      log.warn(
          "ローカルかテストの環境でないため、代表データを入れません: OTEL_DEPLOYMENT_ENVIRONMENT_NAME={}, DB_HOST={}",
          environment,
          host);
      return;
    }
    requireLocalEnvironment(env);
    final String url = jdbcUrl(env);
    final Connection connection;
    try {
      connection = DriverManager.getConnection(url, connectionProperties(env));
    } catch (SQLException e) {
      if (mode == Mode.AUTO) {
        // getMessage はユーザー名を含みうるので出さない。
        final String sqlState = e.getSQLState();
        final String exception = e.getClass().getSimpleName();
        log.warn("DB に接続できないため、代表データを入れません: sqlState={}, exception={}", sqlState, exception);
        return;
      }
      throw e;
    }
    try (connection) {
      final Outcome outcome =
          DSL.using(connection, SQLDialect.POSTGRES, SETTINGS)
              .transactionResult(cfg -> new LocalDataSeeder(SEED_CLOCK).seed(cfg.dsl(), mode));
      log.info("代表データの投入の結果: {}", outcome);
    }
  }

  private static boolean isLoopbackHost(final String host) {
    if (LOCALHOST.equals(host)) {
      return true;
    }
    try {
      // 127.0.0.0/8 と ::1。DNS を引かず、名前は IllegalArgumentException になる。
      return InetAddress.ofLiteral(host).isLoopbackAddress();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * application.yaml の spring.datasource.url と同じ形の URL を作る。DB_URL は Gradle だけの上書きでアプリの接続先ではなく、 読むと
   * DB_HOST の判定をすり抜けるので読まない。
   */
  private static String jdbcUrl(final Map<String, String> env) {
    return "jdbc:postgresql://"
        + required(env, DB_HOST)
        + ":"
        + required(env, "DB_PORT")
        + "/"
        + required(env, "DB_NAME");
  }

  private static Properties connectionProperties(final Map<String, String> env) {
    final Properties properties = new Properties();
    properties.setProperty("user", required(env, "DB_USERNAME"));
    properties.setProperty("password", required(env, "DB_PASSWORD"));
    // 動いているアプリと行のロックで競合したときに、無期限に待たない。
    properties.setProperty(
        "options", "-c TimeZone=UTC -c lock_timeout=5000 -c statement_timeout=30000");
    return properties;
  }

  private static String required(final Map<String, String> env, final String name) {
    final String value = env.get(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("環境変数 " + name + " がありません");
    }
    return value;
  }

  private static String name(final Table<?> table) {
    final Schema schema = table.getSchema();
    return (schema == null ? "" : schema.getName() + ".") + table.getName();
  }

  /** 乱数の 2 つの long から UUID v4（ADR-060）の形の値を作る。 */
  private static UUID uuidV4(final Random random) {
    final long most = (random.nextLong() & ~0xF000L) | 0x4000L;
    final long least = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
    return new UUID(most, least);
  }
}
