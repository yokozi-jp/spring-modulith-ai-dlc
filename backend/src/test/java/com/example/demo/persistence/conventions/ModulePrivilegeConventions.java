package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.CatalogSnapshot.ModuleRoleRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import com.example.demo.shared.persistence.ModuleRoles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * モジュールのロール（{@code <module>_module}）の形と権限の規則（G4、G5）を、カタログの行から判定する。
 *
 * <p>ロールの一覧は、{@link
 * ModuleRoles#MODULES}と一致する。各ロールはログインできず、DDLの権限もなく、アプリロールは切り替えだけができる。権限は、自分のスキーマの表のDML、依存の方向にあるモジュールのスキーマの表のSELECT、イベントの出版の表へのINSERTだけで、期待する集合と完全に一致する（ADR-094）。
 */
final class ModulePrivilegeConventions {

  /** 規則の根拠の文書。 */
  private static final String DOC =
      "docs/database/connections.md、docs/adr/ADR-094-switch-to-module-database-roles.md";

  /** イベントの出版のスキーマ。 */
  private static final String MODULITH = "modulith";

  /** イベントの出版の表。 */
  private static final String EVENT_PUBLICATION = "modulith.event_publication";

  /** 自分のスキーマの表に与えるDML。 */
  private static final List<String> OWN_PRIVILEGES =
      List.of("SELECT", "INSERT", "UPDATE", "DELETE");

  private ModulePrivilegeConventions() {
    // 静的メソッドだけを持つ。
  }

  /**
   * モジュールのロールの一覧と形を判定する（G4）。
   *
   * @param roles 名前が{@code _module}で終わるロール
   * @param modules ロールを持つはずのモジュール
   * @return 違反
   */
  /* package */ static List<ConventionViolation> roles(
      final List<ModuleRoleRow> roles, final Set<String> modules) {
    final List<ConventionViolation> violations = new ArrayList<>();
    final Set<String> expected =
        modules.stream().map(ModuleRoles::roleOf).collect(Collectors.toCollection(TreeSet::new));
    final Set<String> actual =
        roles.stream()
            .map(role -> role.role().name())
            .collect(Collectors.toCollection(TreeSet::new));
    expected.stream()
        .filter(name -> !actual.contains(name))
        .forEach(name ->
            violations.add(
                roleViolation(name, "MISSING", "ModuleRoles.MODULESのモジュールのロールを環境を作る手順で作る")));
    actual.stream()
        .filter(name -> !expected.contains(name))
        .forEach(name ->
            violations.add(
                roleViolation(name, "UNEXPECTED", "ロールに対応するモジュールをModuleRoles.MODULESに足すか、ロールを消す")));
    for (final ModuleRoleRow row : roles) {
      final String name = row.role().name();
      SchemaPrivilegeConventions.ROLE_ATTRIBUTES.forEach((attribute, granted) -> {
        if (granted.test(row.role())) {
          violations.add(roleViolation(name, attribute, "モジュールのロールにDDLにつながる属性を与えない"));
        }
      });
      if (row.login()) {
        violations.add(roleViolation(name, "LOGIN", "モジュールのロールはログインできないロールにする"));
      }
      if (!row.appRoleCanSet()) {
        violations.add(roleViolation(name, "APP_ROLE_CANNOT_SET", "アプリロールにSET TRUEでメンバーにする"));
      }
      if (row.appRoleInherits()) {
        violations.add(roleViolation(name, "APP_ROLE_INHERITS", "アプリロールをINHERIT FALSEのメンバーにする"));
      }
    }
    return violations;
  }

  /**
   * モジュールのロールの権限が、期待する集合と完全に一致するか判定する（G5）。
   *
   * @param roles 名前が{@code _module}で終わるロール
   * @param dependencies モジュールの直接の依存先（機能モジュールだけ）
   * @param relations テーブルとビュー
   * @return 違反
   */
  /* package */ static List<ConventionViolation> privileges(
      final List<ModuleRoleRow> roles,
      final Map<String, Set<String>> dependencies,
      final List<RelationRow> relations) {
    final Map<String, List<String>> tablesBySchema =
        relations.stream()
            .collect(
                Collectors.groupingBy(
                    RelationRow::schema,
                    Collectors.mapping(RelationRow::name, Collectors.toList())));
    final List<ConventionViolation> violations = new ArrayList<>();
    for (final ModuleRoleRow row : roles) {
      final String name = row.role().name();
      final String module = name.substring(0, name.length() - "_module".length());
      if (!dependencies.containsKey(module)) {
        continue;
      }
      final Set<String> expected = expected(module, dependencies.get(module), tablesBySchema);
      difference(row.privileges(), expected)
          .forEach(privilege -> violations.add(privilegeViolation(name, "unexpected", privilege)));
      difference(expected, row.privileges())
          .forEach(privilege -> violations.add(privilegeViolation(name, "missing", privilege)));
    }
    return violations;
  }

  private static Set<String> expected(
      final String module,
      final Collection<String> dependencies,
      final Map<String, List<String>> tablesBySchema) {
    final Set<String> expected = new HashSet<>();
    expected.add("USAGE:" + module);
    tablesBySchema
        .getOrDefault(module, List.of())
        .forEach(table ->
            OWN_PRIVILEGES.forEach(
                privilege -> expected.add(privilege + ":" + module + "." + table)));
    for (final String dependency : dependencies) {
      expected.add("USAGE:" + dependency);
      tablesBySchema
          .getOrDefault(dependency, List.of())
          .forEach(table -> expected.add("SELECT:" + dependency + "." + table));
    }
    expected.add("USAGE:" + MODULITH);
    expected.add("INSERT:" + EVENT_PUBLICATION);
    return expected;
  }

  private static List<String> difference(final Set<String> left, final Set<String> right) {
    return left.stream().filter(value -> !right.contains(value)).sorted().toList();
  }

  private static ConventionViolation roleViolation(
      final String role, final String detail, final String fix) {
    return new ConventionViolation("G4", role + ":" + detail, fix + "（" + DOC + "）。");
  }

  private static ConventionViolation privilegeViolation(
      final String role, final String kind, final String privilege) {
    return new ConventionViolation(
        "G5",
        role + ":" + kind + ":" + privilege,
        "モジュールのロールの権限を、自分のスキーマのDML、依存の方向のSELECT、イベントの出版へのINSERTだけにする（" + DOC + "）。");
  }
}
