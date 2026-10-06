package com.example.demo.testkit;

import static com.example.demo.jooq.tables.FixtureItemDetailTable.FIXTURE_ITEM_DETAIL;
import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.jooq.DSLContext;
import org.jooq.Name;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * テスト専用の {@code fixture} スキーマに、{@code t_fixture_item} と子の {@code t_fixture_item_detail} を作る JUnit
 * 拡張。
 *
 * <p>業務テーブルがまだないため、shared の共通処理（{@code TableWriter} など）を実 PostgreSQL で確かめるテストだけが使う。
 *
 * <p>本番と同じく、DDL はマイグレーションロールで実行し、アプリロールには DML だけを付与する（ADR-011）。テストはアプリロールの接続で読み書きする。
 * 二つのセッションのテストは同じテーブルを共有する必要があり、セッションに閉じた一時テーブルでは書けないため、実テーブルを作る。
 *
 * <p>テストクラスは順に実行され、{@code afterAll} でスキーマを消すため、スキーマ検査（{@code SchemaInspectionTest}）はこのスキーマを見ない。
 */
public final class FixtureTablesExtension implements BeforeAllCallback, AfterAllCallback {

  /** テスト専用のスキーマ。 */
  private static final Name FIXTURE_SCHEMA = DSL.name("fixture");

  @Override
  public void beforeAll(final ExtensionContext context) throws SQLException {
    runAsMigrationRole(
        context,
        (dsl, appRole) -> {
          dsl.dropSchemaIfExists(FIXTURE_SCHEMA).cascade().execute();
          dsl.createSchema(FIXTURE_SCHEMA).execute();
          dsl.createTable(FIXTURE_ITEM)
              .columns(FIXTURE_ITEM.fields())
              .primaryKey(FIXTURE_ITEM.ITEM_ID)
              .execute();
          dsl.createTable(FIXTURE_ITEM_DETAIL)
              .columns(FIXTURE_ITEM_DETAIL.fields())
              .primaryKey(FIXTURE_ITEM_DETAIL.ITEM_ID, FIXTURE_ITEM_DETAIL.DETAIL_NO)
              .execute();
          dsl.query("GRANT USAGE ON SCHEMA {0} TO {1}", FIXTURE_SCHEMA, appRole).execute();
          dsl.query(
                  "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA {0} TO {1}",
                  FIXTURE_SCHEMA, appRole)
              .execute();
        });
  }

  @Override
  public void afterAll(final ExtensionContext context) throws SQLException {
    runAsMigrationRole(
        context, (dsl, appRole) -> dsl.dropSchemaIfExists(FIXTURE_SCHEMA).cascade().execute());
  }

  /** テストの Spring の設定から接続先を読み、マイグレーションロールで DDL を実行する。 */
  private static void runAsMigrationRole(
      final ExtensionContext context, final MigrationStatements statements) throws SQLException {
    final Environment environment = SpringExtension.getApplicationContext(context).getEnvironment();
    final Name appRole = DSL.name(environment.getRequiredProperty("spring.datasource.username"));
    try (Connection connection =
        DriverManager.getConnection(
            environment.getRequiredProperty("spring.datasource.url"),
            environment.getRequiredProperty("MIGRATION_DB_USERNAME"),
            environment.getRequiredProperty("MIGRATION_DB_PASSWORD"))) {
      statements.run(DSL.using(connection, SQLDialect.POSTGRES), appRole);
    }
  }

  /** マイグレーションロールの接続で実行する DDL。 */
  @FunctionalInterface
  private interface MigrationStatements {

    /** DDL を実行する。 */
    void run(DSLContext dsl, Name appRole);
  }
}
