package com.example.demo.persistence.conventions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import com.example.demo.shared.persistence.ModuleRoles;
import com.example.demo.testkit.DatabaseTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.core.ApplicationModules;

/**
 * マイグレーション済みのテストDBを、アプリロールで{@code pg_catalog}から読んで検査する。
 *
 * <p>業務の表がテーブル、テナント、外部キーの規約を満たすことと、アプリロールが業務のスキーマに権限を持たないこと、モジュールのロールが依存の方向の権限だけを持つこと、マイグレーションロールがsuperuserでもBYPASSRLSでもないことを確かめる。DBのテーブルが保持期限と個人データの一覧に載っていることも確かめる。判定の拒否経路は{@link
 * SchemaConventionsTest}が違反を含む行で検証する。
 */
@DatabaseTest
class SchemaInspectionTest {

  /** テーブルごとの保持期限と個人データの有無の一覧。{@code backend}をカレントにして実行する。 */
  private static final Path RETENTION_DOCUMENT =
      Path.of("../docs/database/table-retention-and-personal-data.md");

  /** 一覧の表がある節の見出し。 */
  private static final String RETENTION_HEADING = "## 保持期限と個人データ";

  /** 一覧の表の行から、{@code スキーマ.テーブル}を取り出す。 */
  private static final Pattern TABLE_ROW =
      Pattern.compile("^\\| `([a-z0-9_]+\\.[a-z0-9_]+)`\\s*\\|", Pattern.MULTILINE);

  /** Liquibaseの管理テーブルのスキーマ。一覧に載せない。 */
  private static final String LIQUIBASE_SCHEMA = "liquibase";

  /** アプリロールで接続したjOOQのコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** changesetを適用したマイグレーションロールの名前。 */
  @Value("${MIGRATION_DB_USERNAME}")
  private String migrationRole;

  @Test
  @DisplayName("マイグレーション後のスキーマは規約を満たす")
  void migratedSchemaConforms() {
    final List<ConventionViolation> violations =
        SchemaConventions.check(
            SchemaCatalog.read(dsl, migrationRole), SchemaConventions.ALLOWLIST);

    assertThat(violations).as("スキーマ検査の違反: %s", violations).isEmpty();
  }

  @Test
  @DisplayName("モジュールのロールは、ログインできず、依存の方向の権限だけを持つ")
  void moduleRolesMatchModuleDependencies() {
    final CatalogSnapshot snapshot = SchemaCatalog.read(dsl, migrationRole);

    final List<ConventionViolation> violations = new ArrayList<>();
    violations.addAll(
        ModulePrivilegeConventions.roles(snapshot.moduleRoles(), ModuleRoles.MODULES));
    violations.addAll(
        ModulePrivilegeConventions.privileges(
            snapshot.moduleRoles(), moduleDependencies(), snapshot.relations()));

    assertThat(violations).as("モジュールのロールの違反: %s", violations).isEmpty();
  }

  /** Spring Modulith が見つけた、機能モジュールの直接の依存先（機能モジュールだけ）。 */
  private static Map<String, Set<String>> moduleDependencies() {
    final ApplicationModules modules = ApplicationModules.of(DemoApplication.class);
    return ModuleRoles.MODULES.stream()
        .collect(
            Collectors.toMap(
                Function.identity(),
                name ->
                    modules
                        .getModuleByName(name)
                        .orElseThrow(() -> new AssertionError("モジュールがない: " + name))
                        .getDirectDependencies(modules)
                        .uniqueModules()
                        .map(module -> module.getIdentifier().toString())
                        .filter(ModuleRoles.MODULES::contains)
                        .collect(Collectors.toSet())));
  }

  @Test
  @DisplayName("テーブルは保持期限と個人データの一覧に載っている")
  void everyTableIsListedInRetentionDocument() throws IOException {
    final Set<String> actual =
        SchemaCatalog.read(dsl, migrationRole).relations().stream()
            .filter(CatalogSnapshot.RelationRow::isTable)
            .filter(row -> !LIQUIBASE_SCHEMA.equals(row.schema()))
            .map(row -> row.schema() + "." + row.name())
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(documentedTables(Files.readString(RETENTION_DOCUMENT)))
        .as("%sの「保持期限と個人データ」の表の行。直し方：テーブルを足す、消すchangesetと同じ変更で行を足す、消す", RETENTION_DOCUMENT)
        .isEqualTo(actual);
  }

  /** 一覧の「保持期限と個人データ」の節にある表の、先頭の列のテーブル名。次の節の表は読まない。 */
  private static Set<String> documentedTables(final String markdown) {
    final int start = markdown.indexOf(RETENTION_HEADING);
    final int end = markdown.indexOf("\n## ", start + RETENTION_HEADING.length());
    final Matcher row =
        TABLE_ROW.matcher(markdown.substring(start, end < 0 ? markdown.length() : end));
    final Set<String> tables = new TreeSet<>();
    while (row.find()) {
      tables.add(row.group(1));
    }
    return tables;
  }

  @Test
  @DisplayName("キーワード一覧はpg_get_keywords()と一致する")
  void keywordFileMatchesServerKeywords() {
    final Set<String> serverKeywords =
        dsl
            .resultQuery("SELECT word FROM pg_get_keywords()")
            .fetch(r -> r.get("word", String.class))
            .stream()
            .collect(Collectors.toSet());

    assertThat(ChangesetTableRules.postgresqlKeywords())
        .as("%s と pg_get_keywords()", ChangesetTableRules.KEYWORDS_FILE)
        .isEqualTo(serverKeywords);
  }
}
