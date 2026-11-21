package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.Changeset.Change;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 権限のSQL（{@code GRANT}、{@code REVOKE}、{@code ALTER DEFAULT PRIVILEGES}）の書き方（G3）を判定する。
 *
 * <p>権限のSQLだけのchangesetは、任意のSQL（M17）の許可リストに載せず、この規則で判定する。権限は、ログインできないモジュールのロール（{@code
 * <module>_module}）に与える。アプリロールに与えてよいのは、モジュールに属さない{@code modulith}スキーマだけである（ADR-094）。
 */
final class ChangesetPrivilegeRules {

  /** 規則の根拠の文書。 */
  private static final String DOC =
      "docs/database/connections.md、docs/adr/ADR-094-switch-to-module-database-roles.md";

  /** この番号以降のchangesetは、業務スキーマの権限をアプリロールに与えない。これより前の付与は、012が取り消す。番号のないファイルは、この番号以降とみなす。 */
  private static final int MODULE_ROLES_SINCE = 12;

  /** changesetに渡すアプリロールの名前のプレースホルダ。 */
  private static final String APP_ROLE = "${appDatabaseUsername}";

  /** アプリロールに権限を与えてよいスキーマ。 */
  private static final String FRAMEWORK_SCHEMA = "modulith";

  /** 権限のSQLの先頭。 */
  private static final Pattern PRIVILEGE_STATEMENT =
      Pattern.compile(
          "^(GRANT|REVOKE|ALTER\\s+DEFAULT\\s+PRIVILEGES)\\b", Pattern.CASE_INSENSITIVE);

  /** 権限の付与の対象を指す{@code ON}。ないものはロールのメンバーシップの付与である。 */
  private static final Pattern ON_CLAUSE = Pattern.compile("\\sON\\s", Pattern.CASE_INSENSITIVE);

  /** {@code PUBLIC}への付与と、権限の再付与。 */
  private static final Pattern PUBLIC_OR_REGRANT =
      Pattern.compile("\\bPUBLIC\\b|\\bWITH\\s+GRANT\\s+OPTION\\b", Pattern.CASE_INSENSITIVE);

  /** {@code ALTER DEFAULT PRIVILEGES}のスキーマの指定。 */
  private static final Pattern IN_SCHEMA =
      Pattern.compile("\\bIN\\s+SCHEMA\\b", Pattern.CASE_INSENSITIVE);

  /** changesetのファイル名の先頭の連番。 */
  private static final Pattern FILE_NUMBER = Pattern.compile("^(\\d{3})");

  /** モジュールのロールの名前。 */
  private static final Pattern MODULE_ROLE = Pattern.compile("^[a-z]+_module$");

  /** {@code GRANT}の権限の並び（{@code GRANT}と{@code ON}の間）。 */
  private static final Pattern GRANTED_PRIVILEGES =
      Pattern.compile("\\bGRANT\\s+(.+?)\\s+ON\\s", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** 付与してよい権限。DMLとスキーマのUSAGE以外はアプリのロールの責務ではない。 */
  private static final Set<String> ALLOWED_PRIVILEGES =
      Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "USAGE");

  /** 権限の対象（{@code ON}と付与先の間）。 */
  private static final Pattern TARGET =
      Pattern.compile(
          "\\sON\\s+(.+?)\\s+(?:TO|FROM)\\s", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** スキーマか表を指す対象。それ以外（データベース、関数、シーケンスなど）には権限を与えない。 */
  private static final Pattern SCHEMA_OR_TABLE_TARGET =
      Pattern.compile(
          "^(?:SCHEMA\\s|ALL\\s+TABLES\\s+IN\\s+SCHEMA\\s|TABLES$|TABLE\\s|(?!(?:DATABASE|FUNCTIONS?|PROCEDURES?|ROUTINES?|SEQUENCES?|SCHEMAS|ALL|LANGUAGE|TYPES?|DOMAIN|FOREIGN|LARGE|TABLESPACE|PARAMETER)\\b))",
          Pattern.CASE_INSENSITIVE);

  /** スキーマの名前を並べる対象の先頭。 */
  private static final Pattern SCHEMA_LIST_TARGET =
      Pattern.compile("^(?:ALL\\s+TABLES\\s+IN\\s+)?SCHEMA\\s+", Pattern.CASE_INSENSITIVE);

  /** 表を並べる対象の先頭の{@code TABLE}。 */
  private static final Pattern TABLE_KEYWORD =
      Pattern.compile("^TABLE\\s+", Pattern.CASE_INSENSITIVE);

  /** {@code ALTER DEFAULT PRIVILEGES}が対象にするスキーマの並び。 */
  private static final Pattern DEFAULT_PRIVILEGES_SCHEMAS =
      Pattern.compile(
          "\\bIN\\s+SCHEMA\\s+(.+?)\\s+(?:GRANT|REVOKE)\\b",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** 付与先か取り消し元のロールの並び。 */
  private static final Pattern ROLE_LIST =
      Pattern.compile(
          "\\s(?:TO|FROM)\\s+(.+?)(?:\\s+(?:WITH|GRANTED\\s+BY)\\s.*|\\s+(?:CASCADE|RESTRICT))?$",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** 規則の一覧。 */
  private static final List<Rule> RULES =
      List.of(
          new Rule(
              statement -> PUBLIC_OR_REGRANT.matcher(statement.text()).find(),
              "PUBLICと権限の再付与（WITH GRANT OPTION）を使わない"),
          new Rule(
              statement ->
                  statement.grants()
                      && !statement.alter()
                      && !ON_CLAUSE.matcher(statement.text()).find(),
              "ロールのメンバーシップの付与（GRANT <ロール> TO <ロール>）をchangesetに書かない"),
          new Rule(
              statement -> statement.grants() && !allowedPrivilege(statement.text()),
              "付与する権限は、スキーマか表のSELECT、INSERT、UPDATE、DELETE、USAGEだけにする"),
          new Rule(
              statement -> statement.alter() && !IN_SCHEMA.matcher(statement.text()).find(),
              "ALTER DEFAULT PRIVILEGESはIN SCHEMAでスキーマごとに書く"),
          new Rule(
              statement ->
                  statement.roles().isEmpty()
                      || !statement.roles().stream().allMatch(ChangesetPrivilegeRules::isKnownRole),
              "付与先と取り消し元は、モジュールのロール（<module>_module）か、アプリロールのプレースホルダだけにする"),
          new Rule(
              statement ->
                  statement.forbidAppRole()
                      && statement.grants()
                      && statement.roles().contains(APP_ROLE)
                      && !schemas(statement.text()).stream().allMatch(FRAMEWORK_SCHEMA::equals),
              "業務スキーマの権限はアプリロールに与えず、モジュールのロールに与える"));

  private ChangesetPrivilegeRules() {
    // 静的メソッドだけを持つ。
  }

  /**
   * SQLのすべての文が権限のSQLか。
   *
   * @param sql {@code sql}変更の本文
   * @return 文が1つ以上あり、すべてが権限のSQLなら{@code true}
   */
  /* package */ static boolean isPrivilegeOnly(final String sql) {
    final List<String> statements = Changeset.statements(sql);
    return !statements.isEmpty()
        && statements.stream().allMatch(statement -> PRIVILEGE_STATEMENT.matcher(statement).find());
  }

  /**
   * 1つのchangesetの権限のSQLを判定する。
   *
   * @param changeset 判定するchangeset
   * @return 違反
   */
  /* package */ static List<ConventionViolation> check(final Changeset changeset) {
    final List<ConventionViolation> violations = new ArrayList<>();
    final String id = changeset.changesetId();
    final boolean mayGrantAppRole =
        number(changeset).filter(number -> number < MODULE_ROLES_SINCE).isPresent();
    statementsOf(changeset.changes().stream())
        .forEach(statement -> check(id, statement, !mayGrantAppRole, violations));
    statementsOf(changeset.rollbackChanges().stream())
        .forEach(statement -> check(id, statement, false, violations));
    return violations;
  }

  private static Stream<String> statementsOf(final Stream<Change> changes) {
    return changes
        .filter(change -> "sql".equals(change.type()))
        .flatMap(change -> Changeset.statements(change.text("sql")).stream())
        .filter(statement -> PRIVILEGE_STATEMENT.matcher(statement).find());
  }

  private static void check(
      final String id,
      final String statement,
      final boolean forbidAppRoleOutsideFramework,
      final List<ConventionViolation> out) {
    final String upper = statement.toUpperCase(Locale.ROOT);
    final Statement parsed =
        new Statement(
            statement,
            upper.contains("GRANT") && !upper.startsWith("REVOKE"),
            upper.startsWith("ALTER"),
            roles(statement),
            forbidAppRoleOutsideFramework);
    RULES.stream()
        .filter(rule -> rule.violated().test(parsed))
        .forEach(rule ->
            out.add(
                new ConventionViolation(
                    "G3", id + ": " + statement, rule.reason() + "（" + DOC + "）。")));
  }

  /**
   * 判定する権限のSQLの1文。
   *
   * @param text 空白を1つにそろえた文
   * @param grants 権限を与える文か（{@code REVOKE}は含まない）
   * @param alter {@code ALTER DEFAULT PRIVILEGES}か
   * @param roles 付与先か取り消し元のロール
   * @param forbidAppRole 業務スキーマの権限をアプリロールに与えることを禁じるか
   */
  private record Statement(
      String text, boolean grants, boolean alter, List<String> roles, boolean forbidAppRole) {}

  /**
   * 権限のSQLの規則。{@code violated}を満たす文は、{@code reason}の違反になる。
   *
   * @param violated 違反の条件
   * @param reason 違反の理由と直し方
   */
  private record Rule(Predicate<Statement> violated, String reason) {}

  private static boolean allowedPrivilege(final String statement) {
    final Matcher privileges = GRANTED_PRIVILEGES.matcher(statement);
    final Matcher target = TARGET.matcher(statement);
    return privileges.find()
        && target.find()
        && SCHEMA_OR_TABLE_TARGET.matcher(target.group(1)).find()
        && split(privileges.group(1)).stream()
            .map(privilege -> privilege.replaceAll("\\s*\\(.*\\)$", "").toUpperCase(Locale.ROOT))
            .allMatch(ALLOWED_PRIVILEGES::contains);
  }

  /**
   * 権限の対象のスキーマを返す。スキーマを書かない表の名前は、どのスキーマか分からないため空の名前にする。
   *
   * @param statement 権限のSQLの1文
   * @return 対象のスキーマ。対象がなければ空の名前だけ
   */
  private static List<String> schemas(final String statement) {
    final Matcher defaults = DEFAULT_PRIVILEGES_SCHEMAS.matcher(statement);
    if (defaults.find()) {
      return split(defaults.group(1));
    }
    final Matcher target = TARGET.matcher(statement);
    if (!target.find()) {
      return List.of("");
    }
    final Matcher schemaList = SCHEMA_LIST_TARGET.matcher(target.group(1));
    if (schemaList.find()) {
      return split(target.group(1).substring(schemaList.end()));
    }
    return split(TABLE_KEYWORD.matcher(target.group(1)).replaceFirst("")).stream()
        .map(table -> table.contains(".") ? table.substring(0, table.indexOf('.')) : "")
        .toList();
  }

  /** {@code ,}で分け、前後の空白と識別子の引用符を外す。 */
  private static List<String> split(final String list) {
    return Arrays.stream(list.split(",")).map(item -> item.strip().replace("\"", "")).toList();
  }

  private static List<String> roles(final String statement) {
    final Matcher matcher = ROLE_LIST.matcher(statement);
    if (!matcher.find()) {
      return List.of();
    }
    return split(matcher.group(1));
  }

  private static boolean isKnownRole(final String role) {
    return APP_ROLE.equals(role) || MODULE_ROLE.matcher(role).matches();
  }

  private static Optional<Integer> number(final Changeset changeset) {
    final Matcher matcher = FILE_NUMBER.matcher(changeset.fileName());
    return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
  }
}
