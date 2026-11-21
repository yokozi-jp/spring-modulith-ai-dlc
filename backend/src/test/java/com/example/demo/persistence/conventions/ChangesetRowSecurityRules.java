package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.Changeset.Change;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 業務の表の行レベルセキュリティの SQL の書き方（S1、S2）を判定する。
 *
 * <p>RLS の SQL は Liquibase OSS の Change Type で書けないため任意の SQL になる。決まった 3 文だけを書かせ、その SQL だけの変更は任意の
 * SQL（M17）の許可リストに載せずにこの規則で判定する。業務の表に RLS とポリシーが揃っていることは、カタログの検査（{@link
 * SchemaTenantConventions}）が確かめる。
 */
final class ChangesetRowSecurityRules {

  /** 規則の根拠の文書。 */
  private static final String DOC = "docs/database/postgresql-multitenancy.md";

  /** RLS とポリシーに触れる文。 */
  private static final Pattern ROW_SECURITY =
      Pattern.compile("\\bROW\\s+LEVEL\\s+SECURITY\\b|\\bPOLICY\\b", Pattern.CASE_INSENSITIVE);

  /** 文の対象の表（{@code スキーマ.表}）。 */
  private static final Pattern TARGET =
      Pattern.compile("^(?:ALTER TABLE|CREATE POLICY \\S+ ON) ([a-z0-9_]+)\\.([a-z0-9_]+) ");

  private ChangesetRowSecurityRules() {
    // 静的メソッドだけを持つ。
  }

  /**
   * 表に書く RLS の 3 文を返す。
   *
   * @param qualifiedTable {@code スキーマ.表}
   * @return ENABLE、FORCE、ポリシーの作成の順の文
   */
  /* package */ static List<String> statementsFor(final String qualifiedTable) {
    return List.of(
        "ALTER TABLE " + qualifiedTable + " ENABLE ROW LEVEL SECURITY",
        "ALTER TABLE " + qualifiedTable + " FORCE ROW LEVEL SECURITY",
        "CREATE POLICY "
            + TenantIsolation.POLICY_NAME
            + " ON "
            + qualifiedTable
            + " AS PERMISSIVE FOR ALL TO PUBLIC USING ("
            + TenantIsolation.POLICY_USING
            + ")");
  }

  /**
   * SQL のすべての文が、RLS の外でないスキーマの表への決まった RLS の文か。
   *
   * @param sql {@code sql} 変更の本文
   * @return 文が 1 つ以上あり、すべてが決まった RLS の文なら {@code true}
   */
  /* package */ static boolean isRowSecurityOnly(final String sql) {
    final List<String> statements = ChangesetPrivilegeRules.statements(sql);
    return !statements.isEmpty()
        && statements.stream().allMatch(statement -> canonicalTarget(statement).isPresent());
  }

  /**
   * 1 つの changeset の RLS の SQL を判定する。
   *
   * @param changeset 判定する changeset
   * @return 違反
   */
  /* package */ static List<ConventionViolation> check(final Changeset changeset) {
    final String id = changeset.changesetId();
    final List<ConventionViolation> violations = new ArrayList<>();
    final Map<String, Set<String>> written = new ConcurrentHashMap<>();
    rowSecurityStatements(changeset.allChanges())
        .forEach(statement -> {
          final Optional<String> table = canonicalTarget(statement);
          if (table.isEmpty()) {
            violations.add(
                new ConventionViolation(
                    "S1",
                    id + ": " + statement,
                    "RLS は、tenancy と modulith 以外のスキーマの表に、"
                        + String.join("; ", statementsFor("<スキーマ>.<表>"))
                        + " の 3 文だけで書く。ポリシーの対象を PUBLIC にし、current_setting の missing_ok を使わない（"
                        + DOC
                        + "）。"));
          } else {
            written.computeIfAbsent(table.get(), key -> new LinkedHashSet<>()).add(statement);
          }
        });
    written.forEach((table, statements) -> {
      if (!statements.containsAll(statementsFor(table))) {
        violations.add(
            new ConventionViolation(
                "S2",
                id + ": " + table,
                "RLS の ENABLE、FORCE、ポリシーの作成の 3 文を同じ changeset に揃える。"
                    + "FORCE がないと表の所有者に RLS が効かず、ポリシーがないと行が見えない（"
                    + DOC
                    + "）。"));
      }
    });
    return violations;
  }

  private static Stream<String> rowSecurityStatements(final Stream<Change> changes) {
    return changes
        .filter(change -> "sql".equals(change.type()))
        .flatMap(change -> ChangesetPrivilegeRules.statements(change.text("sql")).stream())
        .filter(statement -> ROW_SECURITY.matcher(statement).find());
  }

  /** 決まった RLS の文なら、その対象の表を返す。 */
  private static Optional<String> canonicalTarget(final String statement) {
    final Matcher matcher = TARGET.matcher(statement);
    if (!matcher.find() || TenantIsolation.RLS_EXEMPT_SCHEMAS.contains(matcher.group(1))) {
      return Optional.empty();
    }
    final String table = matcher.group(1) + "." + matcher.group(2);
    return statementsFor(table).contains(statement) ? Optional.of(table) : Optional.empty();
  }
}
