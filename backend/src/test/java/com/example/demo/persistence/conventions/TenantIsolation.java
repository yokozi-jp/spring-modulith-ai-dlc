package com.example.demo.persistence.conventions;

import static com.example.demo.shared.infrastructure.persistence.ModuleRoleTransactionManager.TENANT_SETTING;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** 業務の表をテナントで分ける RLS の決まった形。changeset の検査とカタログの検査が同じ形を使う。 */
final class TenantIsolation {

  /** RLS の外に置くスキーマ。{@code modulith} はフレームワークの表、{@code tenancy} はテナントが決まる前に引く表を持つ。 */
  /* package */ static final Set<String> RLS_EXEMPT_SCHEMAS = Set.of("modulith", "tenancy");

  /** 違反のメッセージに書く、RLS の外に置くスキーマの名前。 */
  /* package */ static final String RLS_EXEMPT_SCHEMA_NAMES =
      String.join(" と ", new TreeSet<>(RLS_EXEMPT_SCHEMAS));

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
    // 定数と静的メソッドだけを持つ。
  }

  /**
   * テナントで分ける表を置くスキーマか。
   *
   * @param schema スキーマ名。changeset の {@code schemaName} がなければ空文字
   * @return 業務スキーマで、RLS の外に置くスキーマでなければ {@code true}
   */
  /* package */ static boolean isTenantScoped(final String schema) {
    return SchemaConventions.isBusinessSchema(schema) && !RLS_EXEMPT_SCHEMAS.contains(schema);
  }

  /**
   * 同じモジュールの表を指す外部キーの列。
   *
   * @param schema 外部キーを持つ表のスキーマ
   * @param key 参照先の主キーの列
   * @return 外部キーの列と、参照先の列の並び
   */
  /* package */ static List<String> foreignKeyColumns(final String schema, final String key) {
    return Stream.concat(
            isTenantScoped(schema) ? Stream.of(TENANT_COLUMN) : Stream.empty(), Stream.of(key))
        .toList();
  }

  /** 違反のメッセージに書く、{@link #foreignKeyColumns} の形。 */
  /* package */ static String foreignKeyShape(final String schema) {
    return isTenantScoped(schema) ? "(" + TENANT_COLUMN + ", 参照先の主キー)" : "参照先の主キーだけ";
  }
}
