package com.example.demo;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Spring を起動せずにローカルかテストの DB へ書くプログラム（{@link LocalDataSeeder}、{@link E2eFixtureServer}）が使う、環境の確認と接続。
 *
 * <p>どちらも環境変数（{@code .env} か slot の env ファイル）から接続先を読み、ローカルかテストの環境でなければ DB に触れずに止まる。
 */
/* package */ final class LocalDatabase {

  /** 環境の目印の変数名。 */
  private static final String ENVIRONMENT = "OTEL_DEPLOYMENT_ENVIRONMENT_NAME";

  /** 接続先のホストの変数名。 */
  private static final String DB_HOST = "DB_HOST";

  /** loopback の名前。 */
  private static final String LOCALHOST = "localhost";

  /** ローカルかテストの環境の目印（OTEL_DEPLOYMENT_ENVIRONMENT_NAME）の値。 */
  private static final List<String> LOCAL_ENVIRONMENTS = List.of("local", "test");

  private LocalDatabase() {}

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

  /**
   * application.yaml の spring.datasource.url と同じ接続先へ接続する。DB_URL は Gradle だけの上書きでアプリの接続先ではなく、読むと
   * DB_HOST の判定をすり抜けるので読まない。
   */
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
    // 動いているアプリと行のロックで競合したときに、無期限に待たない。
    properties.setProperty(
        "options", "-c TimeZone=UTC -c lock_timeout=5000 -c statement_timeout=30000");
    return DriverManager.getConnection(url, properties);
  }

  /** 環境変数の値を返す。無いか空なら {@link IllegalStateException} を投げる。 */
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
