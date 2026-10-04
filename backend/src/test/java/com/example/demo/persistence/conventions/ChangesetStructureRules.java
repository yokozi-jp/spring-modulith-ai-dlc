package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.Changeset.Change;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * changesetのファイル名と構造の規則（M2、M4、M6、M9、M12、M13からM17）を判定する。
 *
 * <p>{@code modulith}スキーマのchangesetを含め、すべてのchangesetに適用する。
 */
final class ChangesetStructureRules {

  /** changesetのidとファイル名の規則を定める文書。 */
  private static final String MIGRATIONS_DOC = "docs/database/migrations.md";

  /** ロックを抑えるスキーマ変更の規則を定める文書。 */
  private static final String ONLINE_CHANGE_DOC =
      "docs/database/postgresql-online-schema-change.md";

  /** changesetのファイル名の形式（{@code NNN-kebab-case.yaml}）。 */
  private static final Pattern FILE_NAME =
      Pattern.compile("^\\d{3}-[a-z0-9]+(?:-[a-z0-9]+)*\\.yaml$");

  /** スキーマ名で修飾すべき名前の属性。 */
  private static final List<String> SCHEMA_SCOPED_KEYS =
      List.of("tableName", "indexName", "sequenceName", "viewName");

  /** {@code CONCURRENTLY}を含むSQL。 */
  private static final Pattern CONCURRENTLY =
      Pattern.compile("\\bCONCURRENTLY\\b", Pattern.CASE_INSENSITIVE);

  /** 理由を{@code comment}に書く必要がある、例外の構文を含むSQL（ENUMとマテリアライズドビュー）。 */
  private static final Pattern EXCEPTION_SQL =
      Pattern.compile(
          "\\bAS\\s+ENUM\\b|\\bCREATE\\s+MATERIALIZED\\s+VIEW\\b", Pattern.CASE_INSENSITIVE);

  private ChangesetStructureRules() {
    // 静的メソッドだけを持つ。
  }

  /**
   * changesetのディレクトリにあるファイル名を判定する（M16）。
   *
   * @param fileNames ディレクトリの全エントリの名前
   * @return 形式に合わない名前と、番号が重複した名前の違反
   */
  /* package */ static List<ConventionViolation> fileNames(final Collection<String> fileNames) {
    final List<ConventionViolation> violations = new ArrayList<>();
    for (final String name : fileNames) {
      if (!FILE_NAME.matcher(name).matches()) {
        violations.add(
            new ConventionViolation(
                "M16",
                name,
                "changesetのディレクトリにはNNN-kebab-case.yamlの形式のファイルだけを置く（" + MIGRATIONS_DOC + "）。"));
      }
    }
    final Map<String, List<String>> byNumber =
        fileNames.stream()
            .filter(name -> FILE_NAME.matcher(name).matches())
            .collect(Collectors.groupingBy(name -> name.substring(0, 3)));
    byNumber.values().stream()
        .filter(names -> names.size() > 1)
        .flatMap(List::stream)
        .sorted()
        .map(
            name ->
                new ConventionViolation(
                    "M16", name, "changesetの番号が他のファイルと重複している。番号を付け直す（" + MIGRATIONS_DOC + "）。"))
        .forEach(violations::add);
    return violations;
  }

  /**
   * ファイルの{@code databaseChangeLog}が1つの{@code changeSet}だけを持つか判定する（M15）。
   *
   * @param fileName ファイル名
   * @param entries {@code databaseChangeLog}の要素
   * @return 違反。なければ空
   */
  /* package */ static Optional<ConventionViolation> oneChangesetPerFile(
      final String fileName, final List<?> entries) {
    final boolean single =
        entries.size() == 1 && YamlNodes.map(entries.getFirst()).containsKey("changeSet");
    if (single) {
      return Optional.empty();
    }
    return Optional.of(
        new ConventionViolation(
            "M15", fileName, "1ファイルには1つのchangeSetだけを書く（" + MIGRATIONS_DOC + "）。"));
  }

  /**
   * 1つのchangesetの構造を判定する。
   *
   * @param changeset 判定するchangeset
   * @return 違反
   */
  /* package */ static List<ConventionViolation> check(final Changeset changeset) {
    final List<ConventionViolation> violations = new ArrayList<>();
    final String id = changeset.changesetId();
    final String stem = changeset.fileName().replaceFirst("\\.yaml$", "");
    if (!id.equals(stem)) {
      violations.add(
          new ConventionViolation(
              "M13",
              changeset.fileName(),
              "changesetのid（" + id + "）をファイル名から拡張子を除いた値にする（" + MIGRATIONS_DOC + "）。"));
    }
    if (!"system".equals(changeset.author())) {
      violations.add(
          new ConventionViolation(
              "M14", id, "changesetのauthorをsystemにする（" + MIGRATIONS_DOC + "）。"));
    }
    if (changeset.changes().stream().anyMatch(change -> "tagDatabase".equals(change.type()))
        && changeset.changes().size() != 1) {
      violations.add(
          new ConventionViolation(
              "M2", id, "tagDatabaseを他のChange Typeと同じchangesetに入れない（" + MIGRATIONS_DOC + "）。"));
    }
    schemaNames(changeset).ifPresent(violations::add);
    violations.addAll(rawSql(changeset));
    exceptionConstructs(changeset).ifPresent(violations::add);
    return violations;
  }

  /** M9：名前を持つ変更が{@code schemaName}を明示しているか。 */
  private static Optional<ConventionViolation> schemaNames(final Changeset changeset) {
    return changeset
        .allChanges()
        .filter(change -> SCHEMA_SCOPED_KEYS.stream().anyMatch(change.attributes()::containsKey))
        .filter(change -> change.schema().isBlank())
        .findFirst()
        .map(
            change ->
                new ConventionViolation(
                    "M9",
                    changeset.changesetId(),
                    change.type()
                        + "にschemaNameを明示する（docs/adr/ADR-011-use-module-owned-database-schemas.md）。"));
  }

  /** M4、M6、M17：任意のSQLを使う変更の規則。 */
  private static List<ConventionViolation> rawSql(final Changeset changeset) {
    final String id = changeset.changesetId();
    final List<ConventionViolation> violations = new ArrayList<>();
    changeset
        .allChanges()
        .map(Change::rawSql)
        .flatMap(Optional::stream)
        .map(
            sql ->
                new ConventionViolation(
                    "M17",
                    id + ": " + sql,
                    "任意のSQLは、Change Typeで書けない理由を付けて許可リスト（ChangesetLint.ALLOWLIST）に載せた場合だけ使う。"))
        .forEach(violations::add);
    final List<String> changeSql =
        changeset.changes().stream().map(Change::rawSql).flatMap(Optional::stream).toList();
    if (!changeSql.isEmpty() && changeset.rollbackChanges().isEmpty()) {
      violations.add(
          new ConventionViolation(
              "M4", id, "任意のSQLを使うchangesetにはrollbackを定義する（" + MIGRATIONS_DOC + "）。"));
    }
    if (changeset.runsInTransaction()
        && changeSql.stream().anyMatch(sql -> CONCURRENTLY.matcher(sql).find())) {
      violations.add(
          new ConventionViolation(
              "M6",
              id,
              "CONCURRENTLYを使うchangesetにはrunInTransaction: falseを指定する（"
                  + ONLINE_CHANGE_DOC
                  + "）。"));
    }
    return violations;
  }

  /** M12：例外の構文を使うchangesetが{@code comment}に理由を書いているか。 */
  private static Optional<ConventionViolation> exceptionConstructs(final Changeset changeset) {
    if (changeset.hasComment()) {
      return Optional.empty();
    }
    final boolean expressionIndex =
        changeset.changes().stream()
            .filter(change -> "createIndex".equals(change.type()))
            .flatMap(change -> change.columns().stream())
            .anyMatch(column -> YamlNodes.isTrue(column.get("computed")));
    final boolean exceptionSql =
        changeset.changes().stream()
            .map(Change::rawSql)
            .flatMap(Optional::stream)
            .anyMatch(sql -> EXCEPTION_SQL.matcher(sql).find());
    if (!expressionIndex && !exceptionSql) {
      return Optional.empty();
    }
    return Optional.of(
        new ConventionViolation(
            "M12",
            changeset.changesetId(),
            "式インデックス、ENUM、マテリアライズドビューを使うときは、理由をchangesetのcommentに書く"
                + "（docs/database/postgresql-constraints-and-indexes.md、postgresql-data-types.md、"
                + "postgresql-database-objects.md）。"));
  }
}
