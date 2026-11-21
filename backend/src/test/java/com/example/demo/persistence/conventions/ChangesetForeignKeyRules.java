package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.Changeset.Change;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 外部キーの changeset の書き方（K4）を判定する。
 *
 * <p>外部キーは {@code addForeignKeyConstraint} だけで作り、名前を {@code fk_{表}} か {@code fk_{連番}_{表}}
 * にし、同じスキーマの表だけを参照し、{@code ON DELETE} と {@code ON UPDATE} の動作を付けない。テナントで分けるスキーマでは {@code
 * (tenant_public_id, 参照先の主キー)} の 2 列、RLS の外のスキーマでは参照先の主キーの 1 列にする。外部キーのない参照の列は、カタログの検査（{@link
 * SchemaTenantConventions#foreignKeys}）が拒否する。
 */
final class ChangesetForeignKeyRules {

  /** 規則の記号。 */
  private static final String RULE = "K4";

  /** 規則の根拠の文書。 */
  private static final String DOC = "docs/database/postgresql-constraints-and-indexes.md";

  /** {@code column} の {@code constraints} で外部キーを作る属性。 */
  private static final List<String> COLUMN_FOREIGN_KEY_KEYS =
      List.of("foreignKeyName", "references", "referencedTableName", "referencedColumnNames");

  /** 参照先の行を消すか変えたときの動作を指定する属性。 */
  private static final List<String> REFERENTIAL_ACTION_KEYS =
      List.of("onDelete", "onUpdate", "deleteCascade");

  private ChangesetForeignKeyRules() {
    // 静的メソッドだけを持つ。
  }

  /**
   * 業務のスキーマへの変更の外部キーを判定する。
   *
   * @param changesets ファイル名の順に並べた changeset
   * @return 違反
   */
  /* package */ static List<ConventionViolation> check(final List<Changeset> changesets) {
    final List<ConventionViolation> violations = new ArrayList<>();
    for (final Changeset changeset : changesets) {
      for (final Change change : changeset.changes()) {
        switch (change.type()) {
          case "createTable", "addColumn" -> columnForeignKeys(change, violations);
          case "addForeignKeyConstraint" -> foreignKey(change, violations);
          default -> {
            // 外部キーを作らない Change Type は判定しない。
          }
        }
      }
    }
    return violations;
  }

  private static void columnForeignKeys(final Change change, final List<ConventionViolation> out) {
    if (!ChangesetLint.isBusinessSchema(change.schema())) {
      return;
    }
    for (final Map<String, Object> column : change.columns()) {
      final Map<String, Object> constraints = YamlNodes.map(column.get("constraints"));
      if (COLUMN_FOREIGN_KEY_KEYS.stream().anyMatch(constraints::containsKey)) {
        out.add(
            new ConventionViolation(
                RULE,
                ChangesetReplay.qualify(change.schema(), change.table())
                    + "."
                    + YamlNodes.text(column, "name"),
                "外部キーは列の constraints でなく addForeignKeyConstraint で作る（" + DOC + "）。"));
      }
    }
  }

  private static void foreignKey(final Change change, final List<ConventionViolation> out) {
    final String schema = change.text("baseTableSchemaName");
    if (!ChangesetLint.isBusinessSchema(schema)) {
      return;
    }
    final String table = change.text("baseTableName");
    final boolean tenantScoped = !TenantIsolation.RLS_EXEMPT_SCHEMAS.contains(schema);
    final List<String> columns = columnList(change.text("baseColumnNames"));
    final List<String> referenced = columnList(change.text("referencedColumnNames"));
    final int width = tenantScoped ? 2 : 1;
    final boolean conforms =
        Pattern.matches("^fk_(\\d+_)?" + Pattern.quote(table) + "$", change.text("constraintName"))
            && !schema.isEmpty()
            && schema.equals(change.text("referencedTableSchemaName"))
            && REFERENTIAL_ACTION_KEYS.stream().noneMatch(change.attributes()::containsKey)
            && columns.size() == width
            && referenced.size() == width
            && (!tenantScoped
                || (TenantIsolation.TENANT_COLUMN.equals(columns.get(0))
                    && TenantIsolation.TENANT_COLUMN.equals(referenced.get(0))));
    if (!conforms) {
      out.add(
          new ConventionViolation(
              RULE,
              ChangesetReplay.qualify(schema, change.text("constraintName")),
              "外部キーは名前を fk_"
                  + table
                  + " か fk_{連番}_"
                  + table
                  + " にし、同じスキーマの表を、"
                  + (tenantScoped ? "(tenant_public_id, 参照先の主キー) の 2 列" : "参照先の主キーの 1 列")
                  + "で参照し、onDelete と onUpdate を付けない（"
                  + DOC
                  + "）。"));
    }
  }

  private static List<String> columnList(final String columns) {
    return Arrays.stream(columns.split(",")).map(String::strip).filter(c -> !c.isEmpty()).toList();
  }
}
