package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.FunctionRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.TypeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ViewDependencyRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * マイグレーション後のスキーマを、{@code pg_catalog}から読んだ行で判定する入口。
 *
 * <p>DBに接続しない純粋な関数である。業務テーブルは{@link
 * #FRAMEWORK_SCHEMAS}以外のスキーマのテーブルとする。DBオブジェクト、拡張機能、独自型、ラージオブジェクトの規則（O1、O3、O4、O7、T10、T12）をここで判定し、テーブルの規則は{@link
 * SchemaTableConventions}、権限の規則は{@link SchemaPrivilegeConventions}が判定する。
 */
final class SchemaConventions {

  /**
   * 業務テーブルの規則を適用しないスキーマ。
   *
   * <p>{@code modulith}はSpring Modulithがスキーマを定めるテーブル、{@code
   * liquibase}はLiquibaseの管理テーブルを持つ（ADR-011）。
   */
  /* package */ static final Set<String> FRAMEWORK_SCHEMAS = Set.of("modulith", "liquibase");

  /**
   * 使ってよい拡張機能。
   *
   * <p>{@code docs/database/postgresql-server-configuration.md}の一覧に、すべてのDBがtemplate1から持つ{@code
   * plpgsql}を加える。{@code auto_explain}は拡張機能ではなく事前に読み込むライブラリなので含めない。
   */
  /* package */ static final Set<String> PERMITTED_EXTENSIONS =
      Set.of("plpgsql", "pg_stat_statements", "pg_hint_plan", "pgaudit", "pg_bigm", "btree_gist");

  /** スキーマ検査の許可リスト。今は例外が要らないため空である。 */
  /* package */ static final List<AllowlistEntry> ALLOWLIST = List.of();

  /** DBオブジェクトの規約の文書。 */
  private static final String OBJECTS_DOC = "docs/database/postgresql-database-objects.md";

  /** データ型の規約の文書。 */
  private static final String DATA_TYPES_DOC = "docs/database/postgresql-data-types.md";

  /** ラージオブジェクトとみなす型。 */
  private static final Set<String> LARGE_OBJECT_TYPES = Set.of("oid", "lo");

  /** DOMAINとENUMの{@code typtype}。 */
  private static final Set<String> USER_DEFINED_TYPE_KINDS = Set.of("d", "e");

  private SchemaConventions() {
    // 静的メソッドだけを持つ。
  }

  /**
   * すべての規則で判定し、許可リストを適用する。
   *
   * @param snapshot カタログの行
   * @param allowlist 許可リスト
   * @return 許可リストで抑止されなかった違反と、使われなかった許可リストの項目
   */
  /* package */ static List<ConventionViolation> check(
      final CatalogSnapshot snapshot, final List<AllowlistEntry> allowlist) {
    final List<ConventionViolation> found = new ArrayList<>();
    Stream.of(
            SchemaTableConventions.triggers(snapshot.triggers()),
            SchemaTableConventions.constraints(snapshot.constraints()),
            SchemaTableConventions.indexes(snapshot.indexes()),
            SchemaTableConventions.primaryKeys(snapshot.relations()),
            SchemaTableConventions.columns(snapshot.columns()),
            functions(snapshot.functions()),
            viewDependencies(snapshot.viewDependencies()),
            inheritance(snapshot.relations()),
            extensions(snapshot.extensions()),
            largeObjects(snapshot.columns(), snapshot.largeObjectCount()),
            userDefinedTypes(snapshot.types(), snapshot.columns()),
            SchemaPrivilegeConventions.appRole(
                snapshot.role(), snapshot.schemaPrivileges(), snapshot.relationPrivileges()))
        .forEach(found::addAll);
    return AllowlistEntry.apply(found, allowlist);
  }

  /**
   * 業務テーブルの規則を適用するスキーマか。
   *
   * @param schema スキーマ名
   * @return {@link #FRAMEWORK_SCHEMAS}に含まれなければ{@code true}
   */
  /* package */ static boolean isBusinessSchema(final String schema) {
    return !FRAMEWORK_SCHEMAS.contains(schema);
  }

  /**
   * O1：業務スキーマに関数とプロシージャを作らない。
   *
   * @param rows 拡張機能が所有しない関数とプロシージャ
   * @return 違反
   */
  /* package */ static List<ConventionViolation> functions(final List<FunctionRow> rows) {
    return rows.stream()
        .filter(row -> isBusinessSchema(row.schema()))
        .map(
            row ->
                new ConventionViolation(
                    "O1",
                    row.schema() + "." + row.signature(),
                    "テーブルを参照する関数を作らず、処理はアプリケーションに書く（" + OBJECTS_DOC + "）。"))
        .toList();
  }

  /**
   * O3：ビューを入れ子にしない。
   *
   * @param rows ビューから別のビューへの依存
   * @return 違反
   */
  /* package */ static List<ConventionViolation> viewDependencies(
      final List<ViewDependencyRow> rows) {
    return rows.stream()
        .filter(row -> isBusinessSchema(row.schema()))
        .map(
            row ->
                new ConventionViolation(
                    "O3",
                    row.schema() + "." + row.view(),
                    "ビューから別のビュー（"
                        + row.referencedSchema()
                        + "."
                        + row.referencedView()
                        + "）を参照しない（"
                        + OBJECTS_DOC
                        + "）。"))
        .toList();
  }

  /**
   * O4：パーティションでないテーブル継承を使わない。
   *
   * @param rows テーブルとビュー
   * @return 違反
   */
  /* package */ static List<ConventionViolation> inheritance(final List<RelationRow> rows) {
    return rows.stream()
        .filter(row -> isBusinessSchema(row.schema()) && row.inheritanceParent() != null)
        .map(
            row ->
                new ConventionViolation(
                    "O4",
                    row.schema() + "." + row.name(),
                    "テーブル継承（INHERITS "
                        + row.inheritanceParent()
                        + "）を使わず、宣言的パーティションを使う（"
                        + OBJECTS_DOC
                        + "）。"))
        .toList();
  }

  /**
   * O7：許可した拡張機能だけを使う。
   *
   * @param names 拡張機能の名前
   * @return 違反
   */
  /* package */ static List<ConventionViolation> extensions(final List<String> names) {
    return names.stream()
        .filter(name -> !PERMITTED_EXTENSIONS.contains(name))
        .map(
            name ->
                new ConventionViolation(
                    "O7",
                    name,
                    "許可していない拡張機能である（docs/database/postgresql-server-configuration.md）。"))
        .toList();
  }

  /**
   * T10：ラージオブジェクトを使わない。
   *
   * @param columns カラム
   * @param largeObjectCount ラージオブジェクトの件数（DB全体）
   * @return 違反
   */
  /* package */ static List<ConventionViolation> largeObjects(
      final List<ColumnRow> columns, final long largeObjectCount) {
    final List<ConventionViolation> violations = new ArrayList<>();
    columns.stream()
        .filter(
            row -> isBusinessSchema(row.schema()) && LARGE_OBJECT_TYPES.contains(row.typeName()))
        .map(
            row ->
                new ConventionViolation(
                    "T10",
                    row.schema() + "." + row.table() + "." + row.column(),
                    "ラージオブジェクトを使わず、オブジェクトストレージにパスを持つ（" + DATA_TYPES_DOC + "）。"))
        .forEach(violations::add);
    if (largeObjectCount > 0) {
      violations.add(
          new ConventionViolation(
              "T10",
              "pg_largeobject_metadata",
              "ラージオブジェクトが" + largeObjectCount + "件ある（" + DATA_TYPES_DOC + "）。"));
    }
    return violations;
  }

  /**
   * T12：DOMAINとENUMを使わない。
   *
   * @param types DOMAINとENUMの型
   * @param columns カラム
   * @return 業務スキーマに定義した型と、業務テーブルのカラムが使う型の違反
   */
  /* package */ static List<ConventionViolation> userDefinedTypes(
      final List<TypeRow> types, final List<ColumnRow> columns) {
    final String detail =
        "DOMAINとENUMを使わず、varchar(n)などの基本の型を使う。ENUMは理由を付けて許可リストに載せる（" + DATA_TYPES_DOC + "）。";
    return Stream.concat(
            types.stream()
                .filter(row -> isBusinessSchema(row.schema()))
                .map(
                    row -> new ConventionViolation("T12", row.schema() + "." + row.name(), detail)),
            columns.stream()
                .filter(
                    row ->
                        isBusinessSchema(row.schema())
                            && USER_DEFINED_TYPE_KINDS.contains(row.typeKind()))
                .map(
                    row ->
                        new ConventionViolation(
                            "T12", row.schema() + "." + row.table() + "." + row.column(), detail)))
        .toList();
  }
}
