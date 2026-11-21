package com.example.demo.persistence.conventions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import com.example.demo.shared.infrastructure.persistence.ModuleRoles;
import com.example.demo.testkit.DatabaseTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
 * <p>業務の表がテーブル、テナント、外部キーの規約を満たすことと、アプリロールが業務のスキーマに権限を持たないこと、モジュールのロールが依存の方向の権限だけを持つこと、マイグレーションロールがsuperuserでもBYPASSRLSでもないことを確かめる。判定の拒否経路は{@link
 * SchemaConventionsTest}が違反を含む行で検証する。
 */
@DatabaseTest
class SchemaInspectionTest {

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
