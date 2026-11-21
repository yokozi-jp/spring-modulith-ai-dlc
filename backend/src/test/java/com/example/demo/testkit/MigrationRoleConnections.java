package com.example.demo.testkit;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import org.springframework.core.env.Environment;

/**
 * 表の所有者のマイグレーションロールで、テストの DB に接続する。
 *
 * <p>DDL、後始末の {@code TRUNCATE}、所有者にも RLS が効くことの確かめに使う。接続先はアプリの接続と同じ {@code
 * spring.datasource.url}、資格情報は {@code MIGRATION_DB_USERNAME} と {@code MIGRATION_DB_PASSWORD} である。
 */
public final class MigrationRoleConnections {

  /** 表のロックを待つ上限。 */
  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

  private MigrationRoleConnections() {}

  /**
   * マイグレーションロールの新しい接続を開く。閉じるのは呼び出し側である。
   *
   * @param environment テストの Spring の設定
   * @return プールの外の接続
   * @throws SQLException 接続できない場合
   */
  public static Connection open(final Environment environment) throws SQLException {
    final Properties properties = new Properties();
    properties.setProperty("user", environment.getRequiredProperty("MIGRATION_DB_USERNAME"));
    properties.setProperty("password", environment.getRequiredProperty("MIGRATION_DB_PASSWORD"));
    // TRUNCATE と DDL は表の排他ロックを取る。残った接続を無期限に待たず、テストを失敗させる。
    properties.setProperty("options", "-c lock_timeout=" + LOCK_TIMEOUT.toMillis());
    return DriverManager.getConnection(
        environment.getRequiredProperty("spring.datasource.url"), properties);
  }
}
