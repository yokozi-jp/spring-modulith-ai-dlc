package com.example.demo;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

/**
 * Spring を起動せずにローカルかテストの DB へ書くプログラム（{@link LocalDataSeeder}、{@link E2eFixtureServer}）が使う、環境の確認と接続。
 */
/* package */ final class LocalDatabase {

  /** IdP の発行者の URL の変数名。test-user と test-admin の所属を、この発行者の利用者として入れる。 */
  /* package */ static final String OIDC_ISSUER_URI = "OIDC_ISSUER_URI";

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

  private LocalDatabase() {}

  // ponytail: DB_HOST の loopback の判定は、SSH のポート転送で STG の DB を localhost に向けた場合を防げない。
  // その場合は目印の OTEL_DEPLOYMENT_ENVIRONMENT_NAME だけが防ぐ。防ぐ必要が出たら、接続後に DB
  // 側の目印（例：current_database()）も確かめる。
  /* package */ static boolean isLocalEnvironment(final Map<String, String> env) {
    final String environment = env.get(ENVIRONMENT);
    final String host = env.get(DB_HOST);
    return environment != null
        && LOCAL_ENVIRONMENTS.contains(environment)
        && host != null
        && isLoopbackHost(host);
  }

  /* package */ static void requireLocalEnvironment(final Map<String, String> env) {
    if (!isLocalEnvironment(env)) {
      throw new IllegalStateException("ローカルかテストの環境でないため実行しません: " + describe(env));
    }
  }

  /** 環境の判定に使う 2 つの変数の値。ログと例外の文言に使う。 */
  /* package */ static String describe(final Map<String, String> env) {
    return ENVIRONMENT + "=" + env.get(ENVIRONMENT) + ", " + DB_HOST + "=" + env.get(DB_HOST);
  }

  /* package */ static Connection connect(final Map<String, String> env) throws SQLException {
    final String url =
        "jdbc:postgresql://"
            + required(env, DB_HOST)
            + ":"
            + required(env, "DB_PORT")
            + "/"
            + required(env, "DB_NAME");
    final Properties properties = new Properties();
    properties.setProperty("user", required(env, "DB_USERNAME"));
    properties.setProperty("password", required(env, "DB_PASSWORD"));
    properties.setProperty(
        "options", "-c TimeZone=UTC -c lock_timeout=5000 -c statement_timeout=30000");
    return DriverManager.getConnection(url, properties);
  }

  /** 実行の SQL をログに出さない、PostgreSQL の方言の jOOQ のコンテキスト。 */
  /* package */ static DSLContext dsl(final Connection connection) {
    return DSL.using(connection, SQLDialect.POSTGRES, SETTINGS);
  }

  /* package */ static String required(final Map<String, String> env, final String name) {
    final String value = env.get(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("環境変数 " + name + " がありません");
    }
    return value;
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
}
