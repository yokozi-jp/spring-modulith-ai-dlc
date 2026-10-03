package com.example.demo.persistence.conventions;

import java.util.List;
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
 * @param role 接続したアプリロール
 * @param schemaPrivileges アプリロールのスキーマへの権限
 * @param relationPrivileges アプリロールのテーブルとビューへの権限
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
    RoleRow role,
    List<SchemaPrivilegeRow> schemaPrivileges,
    List<RelationPrivilegeRow> relationPrivileges) {

  /**
   * {@code pg_class}の1行。
   *
   * @param schema スキーマ名
   * @param name テーブル名
   * @param relkind {@code relkind}（{@code r}、{@code p}、{@code v}、{@code m}、{@code f}）
   * @param hasPrimaryKey 主キーがあるか
   * @param inheritanceParent パーティションでない継承の親テーブル。なければ{@code null}
   */
  /* package */ record RelationRow(
      String schema,
      String name,
      String relkind,
      boolean hasPrimaryKey,
      @Nullable String inheritanceParent) {}

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
   */
  /* package */ record ColumnRow(
      String schema,
      String table,
      String column,
      String typeName,
      String typeKind,
      String identity,
      String generated,
      @Nullable String defaultExpression) {}

  /**
   * {@code pg_constraint}の1行。
   *
   * @param schema スキーマ名
   * @param table テーブル名
   * @param name 制約名
   * @param type {@code contype}
   */
  /* package */ record ConstraintRow(String schema, String table, String name, String type) {}

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
      boolean backsExclusionConstraint) {}

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
   * 接続したロールの属性。
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
}
