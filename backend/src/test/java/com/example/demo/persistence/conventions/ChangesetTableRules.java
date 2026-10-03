package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.ChangesetReplay.ColumnShape;
import com.example.demo.persistence.conventions.ChangesetReplay.TableShape;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 業務テーブルの最終形について、命名と型の規則（N1、N3、N5、N6、N11、T1）を判定する。
 *
 * <p>呼び出す側が、{@code modulith}スキーマのテーブルを除いてから渡す。
 */
final class ChangesetTableRules {

  /** PostgreSQLのキーワードの一覧のファイル（backendのモジュールルートからの相対パス）。 */
  /* package */ static final Path KEYWORDS_FILE =
      Path.of("src", "test", "resources", "conventions", "postgresql-keywords.txt");

  /** 命名規約の文書。 */
  private static final String NAMING_DOC = "docs/database/postgresql-naming.md";

  /** テーブル名の種別の接頭辞。 */
  private static final Pattern TABLE_PREFIX =
      Pattern.compile("^(m|t|w|wr|ws|s|sd|sw|sm|h|v|mv|fdw|tmp)_[a-z0-9_]+$");

  /** 論理削除のカラム名（{@code deleted_flg}、{@code is_deleted}など）。 */
  private static final Pattern LOGICAL_DELETE =
      Pattern.compile("^(is_)?(del|delete|deleted)(_(flg|flag|at))?$");

  /** 使わない型（正規化した型に対する判定）。 */
  private static final Pattern BANNED_TYPE =
      Pattern.compile(
          "^(char(\\(\\d+\\))?|text|varchar|serial|bigserial|smallserial|smallint|real|money)$");

  /** PostgreSQLのキーワード（{@code pg_get_keywords()}の全カテゴリ）。 */
  private static final Set<String> KEYWORDS = postgresqlKeywords();

  private ChangesetTableRules() {
    // 静的メソッドだけを持つ。
  }

  /**
   * 業務テーブルの最終形を判定する。
   *
   * @param tables 業務テーブルの最終形
   * @return 違反
   */
  /* package */ static List<ConventionViolation> check(final Collection<TableShape> tables) {
    final List<ConventionViolation> violations = new ArrayList<>();
    for (final TableShape table : tables) {
      tableName(table, violations);
      violations.addAll(ChangesetCommonColumnRules.check(table));
      table.columns().forEach((name, column) -> column(table, name, column, violations));
    }
    return violations;
  }

  /**
   * キーワードの一覧のファイルを読む。{@code #}で始まる行と空行は除く。
   *
   * @return キーワードの集合
   */
  /* package */ static Set<String> postgresqlKeywords() {
    try {
      return Files.readAllLines(KEYWORDS_FILE, StandardCharsets.UTF_8).stream()
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .collect(Collectors.toUnmodifiableSet());
    } catch (final IOException e) {
      throw new UncheckedIOException("キーワードの一覧を読めない: " + KEYWORDS_FILE, e);
    }
  }

  private static void tableName(final TableShape table, final List<ConventionViolation> out) {
    final String target = table.qualifiedName();
    if (!TABLE_PREFIX.matcher(table.name()).matches()) {
      out.add(
          new ConventionViolation(
              "N5", target, "テーブル名に種別の接頭辞（m_、t_、w_など）を付ける（" + NAMING_DOC + "）。"));
    }
    if (KEYWORDS.contains(table.name())) {
      out.add(keyword(target));
    }
  }

  private static void column(
      final TableShape table,
      final String name,
      final ColumnShape column,
      final List<ConventionViolation> out) {
    final String target = table.qualifiedName() + "." + name;
    if (KEYWORDS.contains(name)) {
      out.add(keyword(target));
    }
    if (name.equals(table.name())) {
      out.add(new ConventionViolation("N3", target, "カラム名をテーブル名と同じにしない（" + NAMING_DOC + "）。"));
    }
    if (LOGICAL_DELETE.matcher(name).matches()) {
      out.add(
          new ConventionViolation(
              "N11",
              target,
              "論理削除のカラムを作らない。業務上の意味を持つカラムか物理削除で表す（docs/database/postgresql-logical-design.md）。"));
    }
    final String expectedType = typeForName(name);
    if (!expectedType.isEmpty() && !expectedType.equals(column.type())) {
      out.add(
          new ConventionViolation(
              "N6",
              target,
              "この名前のカラムは"
                  + expectedType
                  + "にする（現在は"
                  + column.type()
                  + "）（docs/adr/ADR-047-detect-column-name-and-type-mismatches.md）。"));
    }
    if (BANNED_TYPE.matcher(column.type()).matches()) {
      out.add(
          new ConventionViolation(
              "T1", target, column.type() + "は使わない型である（docs/database/postgresql-data-types.md）。"));
    }
  }

  /** N6：名前の接尾辞と接頭辞が決める型。決まっていなければ空文字。 */
  private static String typeForName(final String name) {
    if (name.endsWith("_at")) {
      return "timestamptz";
    }
    if (name.endsWith("_date")) {
      return "date";
    }
    if (name.startsWith("is_") || name.startsWith("has_")) {
      return "boolean";
    }
    return "";
  }

  private static ConventionViolation keyword(final String target) {
    return new ConventionViolation(
        "N1", target, "PostgreSQLのキーワードをテーブル名とカラム名に使わない（" + NAMING_DOC + "）。");
  }
}
