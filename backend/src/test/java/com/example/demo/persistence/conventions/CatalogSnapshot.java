package com.example.demo.persistence.conventions;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code pg_catalog}から読んだ、スキーマ検査に使う行。
 *
 * <p>判定のロジックを持たない。{@link SchemaCatalog}が作り、{@link SchemaConventions}が判定する。単体テストは違反を含む行を直接組み立てる。
 *
 * @param relations テーブル、ビュー、外部テーブル
 * @param columns テーブルのカラム
 * @param constraints テーブルの制約
 * @param indexes インデックス
 * @param triggers 内部でないトリガー
 * @param types DOMAINとENUMの型
 * @param functions 拡張機能が所有しない関数とプロシージャ
 * @param viewDependencies ビューから別のビューへの依存
 * @param extensions 拡張機能の名前
 * @param largeObjectCount ラージオブジェクトの件数
 * @param appRole 接続したアプリロール
 * @param migrationRole changesetを適用したマイグレーションロール
 * @param schemaPrivileges アプリロールのスキーマへの権限
 * @param relationPrivileges アプリロールのテーブルとビューへの権限
 * @param moduleRoles 名前が {@code _module} で終わるロール（モジュールのロール）
 * @param policies 行レベルセキュリティのポリシー
 */
record CatalogSnapshot(
    List<RelationRow> relations,
    List<ColumnRow> columns,
    List<ConstraintRow> constraints,
    List<IndexRow> indexes,
    List<TriggerRow> triggers,
    List<TypeRow> types,
    List<FunctionRow> functions,
    List<ViewDependencyRow> viewDependencies,
    List<String> extensions,
    long largeObjectCount,
    RoleRow appRole,
    RoleRow migrationRole,
    List<SchemaPrivilegeRow> schemaPrivileges,
    List<RelationPrivilegeRow> relationPrivileges,
    List<ModuleRoleRow> moduleRoles,
    List<PolicyRow> policies) {

  /**
   * {@code pg_class}の1行。
   *
   * @param schema スキーマ名
   * @param name テーブル名
   * @param relkind {@code relkind}（{@code r}、{@code p}、{@code v}、{@code m}、{@code f}）
   * @param primaryKey 主キーの先頭の列名。主キーがなければ{@code null}
   * @param inheritanceParent パーティションでない継承の親テーブル。なければ{@code null}
   * @param rowSecurity 行レベルセキュリティを有効にしたか（{@code relrowsecurity}）
   * @param forceRowSecurity 表の所有者にも行レベルセキュリティを効かせるか（{@code relforcerowsecurity}）
   * @param comment 論理名のコメント（{@code obj_description}）。なければ空文字
   */
  /* package */ record RelationRow(
      String schema,
      String name,
      String relkind,
      @Nullable String primaryKey,
      @Nullable String inheritanceParent,
      boolean rowSecurity,
      boolean forceRowSecurity,
      String comment) {

    /** テーブルの{@code relkind}（通常のテーブルとパーティションテーブル）。 */
    private static final Set<String> TABLE_KINDS = Set.of("r", "p");

    /** 行レベルセキュリティのないテーブルかビュー。 */
    /* package */ RelationRow(
        final String schema,
        final String name,
        final String relkind,
        final @Nullable String primaryKey,
        final @Nullable String inheritanceParent) {
      this(schema, name, relkind, primaryKey, inheritanceParent, false, false);
    }

    /** コメントのないテーブルかビュー。 */
    /* package */ RelationRow(
        final String schema,
        final String name,
        final String relkind,
        final @Nullable String primaryKey,
        final @Nullable String inheritanceParent,
        final boolean rowSecurity,
        final boolean forceRowSecurity) {
      this(schema, name, relkind, primaryKey, inheritanceParent, rowSecurity, forceRowSecurity, "");
    }

    /** ビューでなく、テーブルか。 */
    /* package */ boolean isTable() {
      return TABLE_KINDS.contains(relkind);
    }
  }

  /**
   * {@code pg_attribute}の1行。
   *
   * @param schema スキーマ名
   * @param table テーブル名
   * @param column カラム名
   * @param typeName {@code format_type}の型名
   * @param typeKind 型の{@code typtype}
   * @param identity {@code attidentity}（{@code a}、{@code d}、空文字）
   * @param generated 生成列の種類（pg_attribute.attgenerated。空文字でなければ生成列）
   * @param defaultExpression 既定値の式。なければ{@code null}
   * @param notNull NOT NULLか（{@code attnotnull}）
   * @param comment 論理名のコメント（{@code col_description}）。なければ空文字
   */
  /* package */ record ColumnRow(
      String schema,
      String table,
      String column,
      String typeName,
      String typeKind,
      String identity,
      String generated,
      @Nullable String defaultExpression,
      boolean notNull,
      String comment) {

    /** NULLを許すカラム。 */
    /* package */ ColumnRow(
        final String schema,
        final String table,
        final String column,
        final String typeName,
        final String typeKind,
        final String identity,
        final String generated,
        final @Nullable String defaultExpression) {
      this(
          schema, table, column, typeName, typeKind, identity, generated, defaultExpression, false);
    }

    /** コメントのないカラム。 */
    /* package */ ColumnRow(
        final String schema,
        final String table,
        final String column,
        final String typeName,
        final String typeKind,
        final String identity,
        final String generated,
        final @Nullable String defaultExpression,
        final boolean notNull) {
      this(
          schema,
          table,
          column,
          typeName,
          typeKind,
          identity,
          generated,
          defaultExpression,
          notNull,
          "");
    }
  }

  /**
   * {@code pg_constraint}の1行。
   *
   * @param schema スキーマ名
   * @param table テーブル名
   * @param name 制約名
   * @param type {@code contype}
   * @param referencedSchema 外部キーが参照するテーブルのスキーマ名。外部キーでなければ{@code null}
   * @param columns 制約の列（{@code conkey}の順）
   * @param referencedTable 外部キーが参照するテーブル名。外部キーでなければ{@code null}
   * @param referencedColumns 外部キーが参照する列（{@code confkey}の順）。外部キーでなければ空
   */
  /* package */ record ConstraintRow(
      String schema,
      String table,
      String name,
      String type,
      @Nullable String referencedSchema,
      List<String> columns,
      @Nullable String referencedTable,
      List<String> referencedColumns) {

    /** 列を判定に使わない制約。 */
    /* package */ ConstraintRow(
        final String schema,
        final String table,
        final String name,
        final String type,
        final @Nullable String referencedSchema) {
      this(schema, table, name, type, referencedSchema, List.of(), null, List.of());
    }
  }

  /**
   * {@code pg_index}の1行。
   *
   * @param schema スキーマ名
   * @param table テーブル名
   * @param name インデックス名
   * @param accessMethod {@code pg_am.amname}
   * @param primary 主キーのインデックスか
   * @param keyColumnCount キーの列数（{@code indnkeyatts}）
   * @param totalColumnCount INCLUDEを含む列数（{@code indnatts}）
   * @param hasExpressions 式を含むか
   * @param hasPredicate 部分インデックスか
   * @param backsExclusionConstraint 排他制約のインデックスか
   * @param unique ユニークインデックスか
   * @param firstColumn 先頭のキーの列名。先頭が式なら{@code null}
   */
  /* package */ record IndexRow(
      String schema,
      String table,
      String name,
      String accessMethod,
      boolean primary,
      int keyColumnCount,
      int totalColumnCount,
      boolean hasExpressions,
      boolean hasPredicate,
      boolean backsExclusionConstraint,
      boolean unique,
      @Nullable String firstColumn) {}

  /**
   * {@code pg_trigger}の1行。
   *
   * @param schema スキーマ名
   * @param table テーブル名
   * @param name トリガー名
   */
  /* package */ record TriggerRow(String schema, String table, String name) {}

  /**
   * {@code pg_type}の1行。
   *
   * @param schema スキーマ名
   * @param name 型名
   * @param kind {@code typtype}（{@code d}か{@code e}）
   */
  /* package */ record TypeRow(String schema, String name, String kind) {}

  /**
   * {@code pg_proc}の1行。
   *
   * @param schema スキーマ名
   * @param signature {@code regprocedure}の表記
   */
  /* package */ record FunctionRow(String schema, String signature) {}

  /**
   * ビューの書き換えルールから、別のビューへの依存。
   *
   * @param schema ビューのスキーマ名
   * @param view ビュー名
   * @param referencedSchema 参照するビューのスキーマ名
   * @param referencedView 参照するビュー名
   */
  /* package */ record ViewDependencyRow(
      String schema, String view, String referencedSchema, String referencedView) {}

  /**
   * ロールの属性。
   *
   * @param name ロール名
   * @param superuser {@code rolsuper}
   * @param createDb {@code rolcreatedb}
   * @param createRole {@code rolcreaterole}
   * @param replication レプリケーションの属性（pg_roles.rolreplication）
   * @param bypassRls {@code rolbypassrls}
   * @param canCreateInDatabase 現在のDBに{@code CREATE}権限があるか
   */
  /* package */ record RoleRow(
      String name,
      boolean superuser,
      boolean createDb,
      boolean createRole,
      boolean replication,
      boolean bypassRls,
      boolean canCreateInDatabase) {}

  /**
   * アプリロールのスキーマへの権限。
   *
   * @param schema スキーマ名
   * @param usage {@code USAGE}権限があるか
   * @param create {@code CREATE}権限があるか
   * @param ownedByRole アプリロールが所有するか
   */
  /* package */ record SchemaPrivilegeRow(
      String schema, boolean usage, boolean create, boolean ownedByRole) {}

  /**
   * アプリロールのテーブルとビューへの権限。
   *
   * @param schema スキーマ名
   * @param relation テーブル名かビュー名
   * @param anyPrivilege いずれかの権限があるか
   * @param truncate {@code TRUNCATE}権限があるか
   * @param references {@code REFERENCES}権限があるか
   * @param trigger {@code TRIGGER}権限があるか
   * @param maintain {@code MAINTAIN}権限があるか
   * @param ownedByRole アプリロールが所有するか
   */
  /* package */ record RelationPrivilegeRow(
      String schema,
      String relation,
      boolean anyPrivilege,
      boolean truncate,
      boolean references,
      boolean trigger,
      boolean maintain,
      boolean ownedByRole) {}

  /**
   * モジュールのロール（{@code <module>_module}）の属性と、アプリロールとの関係と、権限。
   *
   * @param role ロールの属性
   * @param login ログインできるか
   * @param appRoleCanSet アプリロールが{@code SET ROLE}でこのロールへ切り替えられるか
   * @param appRoleInherits アプリロールがこのロールの権限を継承するか
   * @param privileges 与えられた権限。スキーマは{@code USAGE:スキーマ}と{@code CREATE:スキーマ}、テーブルとビューは{@code
   *     権限:スキーマ.名前}
   */
  /* package */ record ModuleRoleRow(
      RoleRow role,
      boolean login,
      boolean appRoleCanSet,
      boolean appRoleInherits,
      Set<String> privileges) {}

  /**
   * {@code pg_policies}の1行。
   *
   * @param schema スキーマ名
   * @param table テーブル名
   * @param name ポリシー名
   * @param permissive {@code PERMISSIVE}か{@code RESTRICTIVE}
   * @param roles 対象のロール。{@code PUBLIC}は{@code public}
   * @param command 対象の操作（{@code ALL}、{@code SELECT}など）
   * @param using {@code USING}の式。なければ{@code null}
   * @param withCheck {@code WITH CHECK}の式。なければ{@code null}
   */
  /* package */ record PolicyRow(
      String schema,
      String table,
      String name,
      String permissive,
      List<String> roles,
      String command,
      @Nullable String using,
      @Nullable String withCheck) {}
}
