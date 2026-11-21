package com.example.demo.testkit;

import static com.example.demo.shared.infrastructure.persistence.ModuleRoleTransactionManager.ROLE_SETTING;
import static com.example.demo.shared.infrastructure.persistence.ModuleRoleTransactionManager.TENANT_SETTING;

import com.example.demo.shared.infrastructure.persistence.ModuleRoleTransactionManager;
import com.example.demo.shared.infrastructure.persistence.ModuleRoles;
import com.example.demo.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;

/**
 * テストの DB の読み書きを、トランザクションの間だけモジュールのロールとテナントで実行する（ADR-094、ADR-107）。
 *
 * <p>アプリロールは業務のスキーマに権限を持たず、業務の表はテナントを設定しないと読み書きできない。本番はトランザクションマネージャーがトランザクションの名前と {@link
 * TenantContext} から設定するが、トランザクションの名前を持たないテストの補助（商品の登録、行の読み戻し）とシーダーは、同じ {@link
 * ModuleRoleTransactionManager#setLocal} で設定する。テナントは {@link TenantContext} に束縛したテナント、束縛していなければ
 * {@link AsTenant#TENANT} にする。
 *
 * <p>設定は呼び出しの間だけで、正常に終わったら元のロールとテナントへ戻す。本体が失敗したときは、jOOQ
 * がセーブポイントまで戻すため、設定も戻る。テストのトランザクションの中でも、アプリロールのままの接続でも使える。
 */
public final class AsModule {

  private AsModule() {}

  /** 表を持つモジュールの名前を返す。モジュールの名前はスキーマ名と同じである。 */
  public static String moduleOf(final Table<?> table) {
    return Objects.requireNonNull(table.getSchema(), "schema").getName();
  }

  /**
   * モジュールのロールとテナントで本体を実行し、結果を返す。
   *
   * @param dsl jOOQ のコンテキスト
   * @param module 切り替えるモジュールの名前
   * @param body モジュールのロールで動かす処理。引数はそのトランザクションの jOOQ のコンテキスト
   * @return 本体の結果
   */
  public static <T> T call(
      final DSLContext dsl, final String module, final Function<DSLContext, T> body) {
    final String tenant = TenantContext.findCurrent().orElse(AsTenant.TENANT).toString();
    return dsl.transactionResult(configuration -> {
      final DSLContext tx = configuration.dsl();
      final Record previous =
          tx.fetchSingle(
              "select current_setting('role'), coalesce(current_setting(?, true), '')",
              TENANT_SETTING);
      setLocal(tx, ModuleRoles.roleOf(module), tenant);
      final T result = body.apply(tx);
      setLocal(tx, previous.get(0, String.class), previous.get(1, String.class));
      return result;
    });
  }

  /** モジュールのロールとテナントで本体を実行する。 */
  public static void run(
      final DSLContext dsl, final String module, final Consumer<DSLContext> body) {
    call(
        dsl,
        module,
        tx -> {
          body.accept(tx);
          return null;
        });
  }

  private static void setLocal(final DSLContext tx, final String role, final String tenant) {
    tx.connection(connection ->
        ModuleRoleTransactionManager.setLocal(
            connection, List.of(Map.entry(ROLE_SETTING, role), Map.entry(TENANT_SETTING, tenant))));
  }
}
