package com.example.demo.persistence.conventions;

import static com.example.demo.shared.infrastructure.persistence.ModuleRoleTransactionManager.TENANT_SETTING;

import java.util.Set;

/** 業務の表をテナントで分ける RLS の決まった形。changeset の検査とカタログの検査が同じ形を使う。 */
final class TenantIsolation {

  /** RLS の外に置くスキーマ。{@code modulith} はフレームワークの表、{@code tenancy} はテナントが決まる前に引く表を持つ。 */
  /* package */ static final Set<String> RLS_EXEMPT_SCHEMAS = Set.of("modulith", "tenancy");

  /** 業務の表が持つテナントの列。 */
  /* package */ static final String TENANT_COLUMN = "tenant_public_id";

  /** 業務の表のポリシーの名前。 */
  /* package */ static final String POLICY_NAME = "tenant_isolation";

  /** changeset に書くポリシーの条件。{@code missing_ok} を使わず、テナントの設定がなければエラーにする。 */
  /* package */ static final String POLICY_USING =
      TENANT_COLUMN + " = current_setting('" + TENANT_SETTING + "')::uuid";

  /** {@code pg_policies.qual} が返す、{@link #POLICY_USING} の式。 */
  /* package */ static final String CATALOG_POLICY_USING =
      "(" + TENANT_COLUMN + " = (current_setting('" + TENANT_SETTING + "'::text))::uuid)";

  /** {@code pg_get_expr} が返す、テナントの列の既定値の式。 */
  /* package */ static final String CATALOG_TENANT_DEFAULT =
      "(current_setting('" + TENANT_SETTING + "'::text))::uuid";

  private TenantIsolation() {
    // 定数だけを持つ。
  }
}
