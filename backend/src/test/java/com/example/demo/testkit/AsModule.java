package com.example.demo.testkit;

import static com.example.demo.shared.infrastructure.persistence.ModuleRoleTransactionManager.TENANT_SETTING;

import com.example.demo.shared.infrastructure.persistence.ModuleRoleTransactionManager;
import com.example.demo.shared.infrastructure.persistence.ModuleRoles;
import com.example.demo.shared.tenant.TenantContext;
import com.example.demo.shared.tenant.TenantId;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.jspecify.annotations.Nullable;

/**
 * テストの DB の読み書きを、トランザクションの間だけモジュールのロールとテナントで実行する（ADR-094、ADR-107）。
 *
 * <p>アプリロールは業務のスキーマに権限を持たず、業務の表はテナントを設定しないと読み書きできない。本番はトランザクションマネージャーがトランザクションの名前と {@link
 * TenantContext} から設定するが、トランザクションの名前を持たないテストの補助（商品の登録、行の読み戻し）とシーダーは、同じ {@link
 * ModuleRoleTransactionManager#setLocal} で設定する。
 *
 * <p>設定は呼び出しの間だけで、正常に終わったら元のロールとテナントへ戻す。PostgreSQL
 * はトランザクションの中で設定を未設定へ戻せないため、元のテナントが未設定なら空文字列へ戻すが、どちらの状態でも、テナントのない業務の表への INSERT と、計画し直した読み取り（{@link
 * #discardCachedPlans}）は失敗する。本体が失敗したときは、jOOQ
 * がセーブポイントまで戻すため、設定も戻る。テストのトランザクションの中でも、アプリロールのままの接続でも使える。
 */
public final class AsModule {

  private AsModule() {}

  /** 表を持つモジュールの名前を返す。モジュールの名前はスキーマ名と同じである。 */
  public static String moduleOf(final Table<?> table) {
    return Objects.requireNonNull(table.getSchema(), "schema").getName();
  }

  /** モジュールのロールとテストの利用者のテナント（{@link AsTenant#TENANT}）で本体を実行し、結果を返す。 */
  public static <T> T call(
      final DSLContext dsl, final String module, final Function<DSLContext, T> body) {
    return call(dsl, module, AsTenant.TENANT, body);
  }

  /**
   * モジュールのロールとテナントで本体を実行し、結果を返す。
   *
   * @param dsl jOOQ のコンテキスト
   * @param module 切り替えるモジュールの名前
   * @param tenant 設定するテナント
   * @param body モジュールのロールで動かす処理。引数はそのトランザクションの jOOQ のコンテキスト
   * @return 本体の結果
   */
  public static <T> T call(
      final DSLContext dsl,
      final String module,
      final TenantId tenant,
      final Function<DSLContext, T> body) {
    return inTransaction(dsl, module, tenant.toString(), body);
  }

  /**
   * テナントを設定せず、モジュールのロールだけで本体を実行し、結果を返す。
   *
   * <p>RLS の外の表（{@code tenancy}）とカタログを読むときと、テナントを設定し忘れた読み書きを DB が止めることを確かめるときに使う。
   */
  public static <T> T callWithoutTenant(
      final DSLContext dsl, final String module, final Function<DSLContext, T> body) {
    return inTransaction(dsl, module, null, body);
  }

  /**
   * 接続に残った実行計画を捨て、次の SQL を今のテナントの設定で計画し直させる。
   *
   * <p>テナントのない業務の表の読み取りは、PostgreSQL
   * が計画を作るときにポリシーの式を評価してエラーになる。テナントのある間に作った計画がプールの接続に残ると、計画を作り直さず、空の表を順に走査して 0
   * 件を返しうる（ADR-107）。パラメーターのない SQL は {@code plan_cache_mode} によらず残った計画を使うので、計画を捨てる。
   */
  public static void discardCachedPlans(final DSLContext tx) {
    tx.execute("discard plans");
  }

  /** モジュールのロールとテストの利用者のテナント（{@link AsTenant#TENANT}）で本体を実行する。 */
  public static void run(
      final DSLContext dsl, final String module, final Consumer<DSLContext> body) {
    run(dsl, module, AsTenant.TENANT, body);
  }

  /** モジュールのロールとテナントで本体を実行する。 */
  public static void run(
      final DSLContext dsl,
      final String module,
      final TenantId tenant,
      final Consumer<DSLContext> body) {
    call(
        dsl,
        module,
        tenant,
        tx -> {
          body.accept(tx);
          return null;
        });
  }

  private static <T> T inTransaction(
      final DSLContext dsl,
      final String module,
      final @Nullable String tenant,
      final Function<DSLContext, T> body) {
    return dsl.transactionResult(configuration -> {
      final DSLContext tx = configuration.dsl();
      final Record previous =
          tx.fetchSingle(
              "select current_setting('role'), coalesce(current_setting(?, true), '')",
              TENANT_SETTING);
      setLocal(tx, ModuleRoles.roleOf(module), tenant);
      final T result = body.apply(tx);
      setLocal(
          tx, previous.get(0, String.class), tenant == null ? null : previous.get(1, String.class));
      return result;
    });
  }

  private static void setLocal(
      final DSLContext tx, final String role, final @Nullable String tenant) {
    tx.connection(connection -> ModuleRoleTransactionManager.setLocal(connection, role, tenant));
  }
}
