package com.example.demo.persistence.conventions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.testkit.DatabaseTest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * マイグレーション済みのテストDBを、アプリロールで{@code pg_catalog}から読んで検査する。
 *
 * <p>業務テーブルはまだないため、規約の検査は{@code modulith}と{@code
 * liquibase}の除外が正しいことと、アプリロールがDMLだけを持つことを確かめる。判定の拒否経路は{@link SchemaConventionsTest}が違反を含む行で検証する。
 */
@DatabaseTest
class SchemaInspectionTest {

  /** アプリロールで接続したjOOQのコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("マイグレーション後のスキーマは規約を満たす")
  void migratedSchemaConforms() {
    final List<ConventionViolation> violations =
        SchemaConventions.check(SchemaCatalog.read(dsl), SchemaConventions.ALLOWLIST);

    assertThat(violations).as("スキーマ検査の違反: %s", violations).isEmpty();
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
