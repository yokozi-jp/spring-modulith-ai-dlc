package com.example.demo.persistence.conventions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Liquibaseのchangesetの静的検査（{@link ChangesetLint}）を、本番のchangesetと違反を含むフィクスチャで検証する。
 *
 * <p>フィクスチャは{@code
 * src/test/resources/conventions/changeset-lint}の下に、1ケース1ディレクトリで置く。違反のケースは、期待する違反のキー（{@code 規則
 * 対象}）の集合と完全に一致することを確かめ、誤検出も失敗にする。
 */
class ChangesetLintTest {

  /** フィクスチャのルート（backendのモジュールルートからの相対パス）。 */
  private static final Path FIXTURES =
      Path.of("src", "test", "resources", "conventions", "changeset-lint");

  /** 既存のテーブルにCONCURRENTLYでインデックスを作るSQL。 */
  private static final String CONCURRENT_INDEX =
      "002-create-idx-1-m-item: CREATE INDEX CONCURRENTLY idx_1_m_item ON item.m_item (item_code);";

  /** {@link #CONCURRENT_INDEX}のrollbackのSQL。 */
  private static final String CONCURRENT_DROP =
      "002-create-idx-1-m-item: DROP INDEX CONCURRENTLY item.idx_1_m_item;";

  /** テーブルにコメントを付けるSQL。 */
  private static final String COMMENT_SQL =
      "002-comment-m-item: COMMENT ON TABLE item.m_item IS 'item';";

  /** {@link #COMMENT_SQL}のrollbackのSQL。 */
  private static final String UNCOMMENT_SQL =
      "002-comment-m-item: COMMENT ON TABLE item.m_item IS NULL;";

  /** 違反のケースの多くが使うテーブル。 */
  private static final String M_ITEM = "item.m_item";

  /** 違反のないケース。 */
  private static final List<LintCase> VALID_CASES =
      List.of(
          lintCase("conforming-table", List.of()),
          lintCase("work-table-omission-with-comment", List.of()),
          lintCase("expression-index-with-comment", List.of()),
          lintCase(
              "concurrent-index-on-existing-table",
              sqlAllowlist(CONCURRENT_INDEX, CONCURRENT_DROP)),
          lintCase("replay-add-column", List.of()),
          lintCase("modulith-excluded", List.of()),
          lintCase("allowlisted-sql", sqlAllowlist(COMMENT_SQL, UNCOMMENT_SQL)));

  /** 違反のケースと、期待する違反のキー。 */
  private static final List<LintCase> INVALID_CASES =
      List.of(
          lintCase("m13-id-differs-from-file-name", "M13 001-create-m-item.yaml"),
          lintCase("m14-author-not-system", "M14 001-create-m-item"),
          lintCase("m15-two-changesets-in-file", "M15 001-create-m-item.yaml"),
          lintCase("m16-file-name-not-kebab", "M16 001_Create.yaml"),
          lintCase("m16-duplicate-number", "M16 001-a.yaml", "M16 001-b.yaml"),
          lintCase("m9-missing-schema-name", "M9 001-create-m-item"),
          lintCase("m2-tag-with-other-change", "M2 001-create-m-item"),
          lintCase("m4-sql-without-rollback", sqlAllowlist(COMMENT_SQL), "M4 002-comment-m-item"),
          lintCase("m17-sql-not-allowlisted", sqlAllowlist(UNCOMMENT_SQL), "M17 " + COMMENT_SQL),
          lintCase(
              "m17-rollback-sql-not-allowlisted",
              sqlAllowlist(COMMENT_SQL),
              "M17 " + UNCOMMENT_SQL),
          lintCase(
              "m17-sql-file",
              sqlAllowlist(UNCOMMENT_SQL),
              "M17 002-comment-m-item: sqlFile sql/comment-m-item.sql"),
          lintCase(
              "m6-concurrently-in-transaction",
              sqlAllowlist(CONCURRENT_INDEX, CONCURRENT_DROP),
              "M6 002-create-idx-1-m-item"),
          lintCase("m12-expression-index-without-comment", "M12 001-create-m-item"),
          lintCase(
              "m12-enum-without-comment",
              sqlAllowlist(
                  "002-create-item-status: CREATE TYPE item.item_status AS ENUM ('active');",
                  "002-create-item-status: DROP TYPE item.item_status;"),
              "M12 002-create-item-status"),
          lintCase(
              "unused-allowlist-entry",
              sqlAllowlist("999-unused: SELECT 1;"),
              "ALLOWLIST M17 999-unused: SELECT 1;"),
          lintCase("m7-create-index-on-existing-table", "M7 item.idx_1_m_item"),
          lintCase(
              "m7-sql-index-without-concurrently",
              sqlAllowlist(
                  "002-create-idx-1-m-item: CREATE INDEX idx_1_m_item ON item.m_item (item_code);",
                  "002-create-idx-1-m-item: DROP INDEX item.idx_1_m_item;"),
              "M7 item.idx_1_m_item"),
          lintCase("m8-add-not-null-on-existing-table", "M8 item.m_item.item_name"),
          lintCase("k3-add-unique-constraint", "K3 " + M_ITEM),
          lintCase("k3-column-unique", "K3 " + M_ITEM),
          lintCase("c1-missing-common-column", "C1 item.m_item.patched_id"),
          lintCase("c1-wrong-type", "C1 item.m_item.created_by"),
          lintCase(
              "c1-not-null-violated", "C1 item.m_item.created_at", "C1 item.m_item.patched_at"),
          lintCase("c1-dropped-by-later-changeset", "C1 item.m_item.lock_no"),
          lintCase("c2-work-table-omits-without-comment", "C2 item.w_item"),
          lintCase("c3-default-on-common-column", "C3 item.m_item.created_at"),
          lintCase("n1-keyword-column", "N1 item.m_item.order"),
          lintCase("n2-uppercase-identifier", "N2 item.m_item.itemName"),
          lintCase("n3-column-same-as-table", "N3 item.m_item.m_item"),
          lintCase(
              "n4-identifier-too-long",
              "N4 item.m_item.item_description_for_the_identifier_length_limit_check_xxxxxxxxx"),
          lintCase("n5-table-without-prefix", "N5 item.goods_item"),
          lintCase("n6-at-not-timestamptz", "N6 item.m_item.ordered_at"),
          lintCase("n6-date-not-date", "N6 item.m_item.sale_date"),
          lintCase("n6-is-not-boolean", "N6 item.m_item.is_active"),
          lintCase("n7-primary-key-name", "N7 item.m_item_pkey"),
          lintCase("n7-index-name", "N7 item.m_item_idx"),
          lintCase("n7-unique-index-name", "N7 item.idx_1_m_item"),
          lintCase("n8-sequence-name", "N8 item.m_item_seq"),
          lintCase("n11-logical-delete-column", "N11 item.m_item.is_deleted"),
          lintCase(
              "t1-banned-types",
              "T1 item.m_item.code_char",
              "T1 item.m_item.memo_text",
              "T1 item.m_item.memo_varchar",
              "T1 item.m_item.row_serial",
              "T1 item.m_item.row_bigserial",
              "T1 item.m_item.small_count",
              "T1 item.m_item.ratio_real",
              "T1 item.m_item.price_money"));

  @Test
  @DisplayName("本番のchangesetは規約を満たす")
  void productionChangesetsConform() {
    final List<ConventionViolation> violations =
        ChangesetLint.lint(ChangesetLint.PRODUCTION_CHANGESETS, ChangesetLint.ALLOWLIST);

    assertThat(violations)
        .as("%s の違反: %s", ChangesetLint.PRODUCTION_CHANGESETS, violations)
        .isEmpty();
  }

  @Test
  @DisplayName("本番の許可リストの各項目に理由がある")
  void productionAllowlistEntriesHaveReasons() {
    assertThat(ChangesetLint.ALLOWLIST)
        .as("ChangesetLint.ALLOWLIST")
        .allSatisfy(entry -> assertThat(entry.reason()).as("%s の理由", entry.key()).isNotBlank());
  }

  @Test
  @DisplayName("フィクスチャのディレクトリとケースの一覧が一致する")
  void everyFixtureDirectoryHasACase() {
    assertThat(directories("valid"))
        .as("%s/valid", FIXTURES)
        .isEqualTo(VALID_CASES.stream().map(LintCase::dir).collect(Collectors.toSet()));
    assertThat(directories("invalid"))
        .as("%s/invalid", FIXTURES)
        .isEqualTo(INVALID_CASES.stream().map(LintCase::dir).collect(Collectors.toSet()));
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("VALID_CASES")
  @DisplayName("規約を満たすchangesetには違反がない")
  void validFixturesHaveNoViolations(final LintCase lintCase) {
    final Path dir = FIXTURES.resolve("valid").resolve(lintCase.dir());

    final List<ConventionViolation> violations = ChangesetLint.lint(dir, lintCase.allowlist());

    assertThat(violations).as("%s の違反: %s", dir, violations).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("INVALID_CASES")
  @DisplayName("規約に反するchangesetは期待した違反だけを報告する")
  void invalidFixturesReportExactlyTheExpectedViolations(final LintCase lintCase) {
    final Path dir = FIXTURES.resolve("invalid").resolve(lintCase.dir());

    final List<ConventionViolation> violations = ChangesetLint.lint(dir, lintCase.allowlist());

    assertThat(violations.stream().map(ConventionViolation::key).collect(Collectors.toSet()))
        .as("%s の違反: %s", dir, violations)
        .isEqualTo(lintCase.expected());
  }

  private static Set<String> directories(final String kind) {
    try (Stream<Path> entries = Files.list(FIXTURES.resolve(kind))) {
      return entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
    } catch (final IOException e) {
      throw new AssertionError("フィクスチャのディレクトリを読めない: " + FIXTURES.resolve(kind), e);
    }
  }

  private static LintCase lintCase(final String dir, final String... expected) {
    return new LintCase(dir, List.of(), Set.of(expected));
  }

  private static LintCase lintCase(
      final String dir, final List<AllowlistEntry> allowlist, final String... expected) {
    return new LintCase(dir, allowlist, Set.of(expected));
  }

  private static List<AllowlistEntry> sqlAllowlist(final String... targets) {
    return Stream.of(targets)
        .map(target -> new AllowlistEntry("M17", target, "フィクスチャで他の規則を検証するため"))
        .toList();
  }

  /**
   * フィクスチャの1ケース。
   *
   * @param dir {@code valid}または{@code invalid}の下のディレクトリ名
   * @param allowlist ケースに渡す許可リスト
   * @param expected 期待する違反のキー（{@code 規則 対象}）
   */
  /* package */ record LintCase(String dir, List<AllowlistEntry> allowlist, Set<String> expected) {

    @Override
    public String toString() {
      return dir;
    }
  }
}
