package com.example.demo;

import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static com.example.demo.jooq.ordering.Tables.T_ORDER_LINE;
import static com.example.demo.jooq.payment.Tables.T_PAYMENT;
import static com.example.demo.jooq.product.Tables.M_PRODUCT;
import static com.example.demo.jooq.tenancy.Tables.M_MEMBERSHIP;
import static com.example.demo.jooq.tenancy.Tables.M_TENANT;

import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.TenancySeeds;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.BusinessSeeds;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.jooq.DSLContext;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * 開発用の代表データ（#114）を、業務データが空のローカルかテストの DB の、{@link TenancySeeds#TENANTS} の各テナントに入れる。テナントと所属（{@link
 * TenancySeeds}）は、業務データの有無によらず、足りない行だけを毎回入れる。
 *
 * <p>業務の表は RLS でテナントごとに分かれるため（ADR-107）、「空」の判定と reset の削除は {@code tenancy.m_tenant} のテナントごとに行う。
 *
 * <p>Gradle の {@code seedLocalData}（JavaExec、テストの runtime classpath）が {@link #main} を呼ぶ。Spring
 * を起動せず、Bean にもしないので、ほかのテストのコンテキストでは動かない。環境の確認、「空」の判定、reset の削除を 1 つのトランザクションで行う。
 */
@Slf4j
// 表ごとのモジュールのロールへの切り替えで、表の static import が増える。
@SuppressWarnings("PMD.TooManyStaticImports")
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

  /** 業務データの表。子から順に並べ、reset はこの順に DELETE する。modulith と tenancy と Liquibase の表は含めない。 */
  /* package */ static final List<Table<?>> BUSINESS_TABLES =
      List.of(T_PAYMENT, T_ORDER_LINE, T_ORDER, M_PRODUCT);

  /** テナントと所属の表。業務データの有無によらず、毎回 {@link TenancySeeds} が足りない行だけを入れる。reset でも消さない。 */
  private static final List<Table<?>> TENANCY_TABLES = List.of(M_TENANT, M_MEMBERSHIP);

  /** Datafaker と UUID の乱数の seed。 */
  private static final long RANDOM_SEED = 114L;

  /** 日時の基準。実行日によらず同じ値にする。 */
  private static final Clock SEED_CLOCK =
      Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);

  /** 日時の基準を取る時計。 */
  private final Clock clock;

  /** test-user と test-admin を所属させる、ローカルの Keycloak の発行者の URL（ID トークンの {@code iss}）。 */
  private final String issuer;

  /** 日時の基準を取る時計と、IdP の発行者の URL を受け取る。 */
  /* package */ LocalDataSeeder(final Clock clock, final String issuer) {
    this.clock = clock;
    this.issuer = issuer;
  }

  /** 呼ぶ側のトランザクションの中で、モードに従って代表データを入れる。 */
  /* package */ Outcome seed(final DSLContext dsl, final Mode mode) {
    final boolean migrated =
        Stream.concat(BUSINESS_TABLES.stream(), TENANCY_TABLES.stream())
            .allMatch(table ->
                AsModule.callWithoutTenant(
                    dsl,
                    AsModule.moduleOf(table),
                    tx ->
                        tx.fetchValue("select to_regclass({0})::text", DSL.inline(tx.render(table)))
                            != null));
    if (!migrated) {
      if (mode == Mode.AUTO) {
        log.warn("業務の表がないため、代表データを入れません。task be-migrate の後に実行してください");
        return Outcome.SKIPPED_NOT_MIGRATED;
      }
      throw new IllegalStateException("業務の表がありません。task be-migrate の後に実行してください");
    }
    final List<TenantId> tenants =
        AsModule.callWithoutTenant(
            dsl,
            "tenancy",
            tx -> {
              TenancySeeds.insert(tx, issuer);
              return tx.select(M_TENANT.PUBLIC_ID)
                  .from(M_TENANT)
                  .orderBy(M_TENANT.TENANT_ID)
                  .fetch(row -> new TenantId(row.value1()));
            });
    if (mode == Mode.RESET) {
      tenants.forEach(tenant ->
          BUSINESS_TABLES.forEach(
              table -> inModuleOf(dsl, tenant, table, tx -> tx.deleteFrom(table).execute())));
    }
    final List<String> nonEmpty =
        tenants.stream()
            .flatMap(tenant ->
                BUSINESS_TABLES.stream()
                    .filter(table -> inModuleOf(dsl, tenant, table, tx -> tx.fetchExists(table)))
                    .map(table -> tenant + ":" + name(table)))
            .toList();
    if (!nonEmpty.isEmpty()) {
      log.info("業務データがあるため、代表データを入れません: {}", nonEmpty);
      return Outcome.SKIPPED_NOT_EMPTY;
    }
    final Instant base = Instant.now(clock);
    final Random random = new Random(RANDOM_SEED);
    final Faker faker = new Faker(Locale.ENGLISH, random);
    final Supplier<UUID> ids = () -> uuidV4(random);
    // faker と ids は同じ random を引く。テナントの順（TenancySeeds.TENANTS）を変えると値が変わる。
    TenancySeeds.TENANTS.forEach(tenant -> BusinessSeeds.insert(dsl, tenant, faker, ids, base));
    return Outcome.SEEDED;
  }

  private static <T> T inModuleOf(
      final DSLContext dsl,
      final TenantId tenant,
      final Table<?> table,
      final Function<DSLContext, T> body) {
    return AsModule.call(dsl, AsModule.moduleOf(table), tenant, body);
  }

  /** 引数のモード（auto、seed、reset）で代表データを入れる。異常のときは例外を投げ、終了コードを 1 にする。 */
  public static void main(final String[] args) throws SQLException {
    run(args, System.getenv());
  }

  /** 指定した環境変数を使い、引数のモードに従って代表データを入れる。 */
  /* package */ static void run(final String[] args, final Map<String, String> env)
      throws SQLException {
    if (args.length == 0) {
      throw new IllegalArgumentException("引数にモード（auto、seed、reset）を渡してください");
    }
    final Mode mode = Mode.valueOf(args[0].toUpperCase(Locale.ROOT));
    if (mode == Mode.AUTO && !LocalDatabase.isLocalEnvironment(env)) {
      final String environment = LocalDatabase.describe(env);
      log.warn("ローカルかテストの環境でないため、代表データを入れません: {}", environment);
      return;
    }
    LocalDatabase.requireLocalEnvironment(env);
    final String issuer = LocalDatabase.required(env, LocalDatabase.OIDC_ISSUER_URI);
    final Connection connection;
    try {
      connection = LocalDatabase.connect(env);
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
          LocalDatabase.dsl(connection)
              .transactionResult(
                  cfg -> new LocalDataSeeder(SEED_CLOCK, issuer).seed(cfg.dsl(), mode));
      log.info("代表データの投入の結果: {}", outcome);
    }
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
