package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.CatalogSnapshot.RelationPrivilegeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RoleRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.SchemaPrivilegeRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * アプリロールの権限の規則（G1）を、カタログの行から判定する。
 *
 * <p>アプリロールはDDLの権限を持たず、自スキーマのUSAGEとテーブルのDMLだけを持ち、{@code liquibase}スキーマに触れない。すべてのスキーマに適用する。
 */
final class SchemaPrivilegeConventions {

  /** Liquibaseの管理テーブルのスキーマ。 */
  private static final String LIQUIBASE_SCHEMA = "liquibase";

  /** 権限の規則の根拠の文書。 */
  private static final String ROLE_DOC =
      "docs/database/connections.md、docs/adr/ADR-011-use-module-owned-database-schemas.md";

  /** ロールの属性の名前と、その属性を持つか。 */
  private static final Map<String, Predicate<RoleRow>> ROLE_ATTRIBUTES =
      Map.of(
          "SUPERUSER", RoleRow::superuser,
          "CREATEDB", RoleRow::createDb,
          "CREATEROLE", RoleRow::createRole,
          "REPLICATION", RoleRow::replication,
          "BYPASSRLS", RoleRow::bypassRls,
          "CREATE:DATABASE", RoleRow::canCreateInDatabase);

  /** DMLでないテーブルの権限の名前と、その権限を持つか。 */
  private static final Map<String, Predicate<RelationPrivilegeRow>> RELATION_PRIVILEGES =
      Map.of(
          "TRUNCATE", RelationPrivilegeRow::truncate,
          "REFERENCES", RelationPrivilegeRow::references,
          "TRIGGER", RelationPrivilegeRow::trigger,
          "MAINTAIN", RelationPrivilegeRow::maintain,
          "OWNER", RelationPrivilegeRow::ownedByRole);

  private SchemaPrivilegeConventions() {
    // 静的メソッドだけを持つ。
  }

  /**
   * G1：アプリロールの権限を判定する。
   *
   * @param role アプリロールの属性
   * @param schemas アプリロールのスキーマへの権限
   * @param relations アプリロールのテーブルとビューへの権限
   * @return 違反
   */
  /* package */ static List<ConventionViolation> appRole(
      final RoleRow role,
      final List<SchemaPrivilegeRow> schemas,
      final List<RelationPrivilegeRow> relations) {
    final List<ConventionViolation> violations = new ArrayList<>();
    ROLE_ATTRIBUTES.forEach(
        (name, granted) -> {
          if (granted.test(role)) {
            violations.add(violation(role, name));
          }
        });
    schemaPrivileges(role, schemas, violations);
    relationPrivileges(role, relations, violations);
    return violations;
  }

  private static void schemaPrivileges(
      final RoleRow role,
      final List<SchemaPrivilegeRow> schemas,
      final List<ConventionViolation> violations) {
    for (final SchemaPrivilegeRow schema : schemas) {
      if (schema.create()) {
        violations.add(violation(role, "CREATE:" + schema.schema()));
      }
      if (schema.ownedByRole()) {
        violations.add(violation(role, "OWNER:" + schema.schema()));
      }
      if (schema.usage() && LIQUIBASE_SCHEMA.equals(schema.schema())) {
        violations.add(violation(role, "USAGE:" + schema.schema()));
      }
    }
  }

  private static void relationPrivileges(
      final RoleRow role,
      final List<RelationPrivilegeRow> relations,
      final List<ConventionViolation> violations) {
    for (final RelationPrivilegeRow relation : relations) {
      final String object = relation.schema() + "." + relation.relation();
      RELATION_PRIVILEGES.forEach(
          (name, granted) -> {
            if (granted.test(relation)) {
              violations.add(violation(role, name + ":" + object));
            }
          });
      if (relation.anyPrivilege() && LIQUIBASE_SCHEMA.equals(relation.schema())) {
        violations.add(violation(role, "ANY:" + object));
      }
    }
  }

  private static ConventionViolation violation(final RoleRow role, final String privilege) {
    return new ConventionViolation(
        "G1",
        role.name() + ":" + privilege,
        "アプリロールにDDLの権限を与えず、自スキーマのUSAGEとテーブルのDMLだけを与える。liquibaseスキーマには権限を与えない（" + ROLE_DOC + "）。");
  }
}
