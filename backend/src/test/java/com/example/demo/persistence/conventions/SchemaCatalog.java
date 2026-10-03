package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ConstraintRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.FunctionRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.IndexRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationPrivilegeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RoleRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.SchemaPrivilegeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.TriggerRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.TypeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ViewDependencyRow;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * 接続したロールで{@code pg_catalog}を読み、{@link CatalogSnapshot}を作る。
 *
 * <p>{@code information_schema}は、ロールに権限のないオブジェクトを見せない。GRANTを忘れたテーブルが検査から漏れないよう、{@code
 * pg_catalog}だけを読む。システムのスキーマを除き、{@code modulith}と{@code liquibase}は含めて読む。業務スキーマへの絞り込みは判定する側が行う。
 */
final class SchemaCatalog {

  /** システムのスキーマ（{@code pg_catalog}、{@code information_schema}、{@code pg_toast}など）を除く条件。 */
  private static final String NOT_SYSTEM =
      "n.nspname <> 'information_schema' AND left(n.nspname, 3) <> 'pg_'";

  /** {@code pg_namespace n}と結合する、テーブルとビューの{@code relkind}。 */
  private static final String RELATION_KINDS = "('r', 'p', 'v', 'm', 'f')";

  /** スキーマ名の列名。 */
  private static final String SCHEMA = "schema_name";

  /** テーブル名の列名。 */
  private static final String TABLE = "table_name";

  /** オブジェクト名の列名。 */
  private static final String NAME = "object_name";

  private SchemaCatalog() {
    // 静的メソッドだけを持つ。
  }

  /**
   * カタログを読む。
   *
   * @param dsl アプリロールで接続したjOOQのコンテキスト
   * @return 読んだ行
   */
  /* package */ static CatalogSnapshot read(final DSLContext dsl) {
    return new CatalogSnapshot(
        relations(dsl),
        columns(dsl),
        dsl.resultQuery(
                query(
                    """
                SELECT n.nspname AS schema_name, c.relname AS table_name, con.conname AS object_name,
                       con.contype::text AS kind
                FROM pg_constraint con
                JOIN pg_class c ON c.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE /*NOT_SYSTEM*/
                """))
            .fetch(
                r ->
                    new ConstraintRow(
                        text(r, SCHEMA), text(r, TABLE), text(r, NAME), text(r, "kind"))),
        indexes(dsl),
        dsl.resultQuery(
                query(
                    """
                SELECT n.nspname AS schema_name, c.relname AS table_name, tg.tgname AS object_name
                FROM pg_trigger tg
                JOIN pg_class c ON c.oid = tg.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE NOT tg.tgisinternal AND /*NOT_SYSTEM*/
                """))
            .fetch(r -> new TriggerRow(text(r, SCHEMA), text(r, TABLE), text(r, NAME))),
        dsl.resultQuery(
                query(
                    """
                SELECT n.nspname AS schema_name, t.typname AS object_name, t.typtype::text AS kind
                FROM pg_type t
                JOIN pg_namespace n ON n.oid = t.typnamespace
                WHERE t.typtype IN ('d', 'e') AND /*NOT_SYSTEM*/
                  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                                  WHERE d.classid = 'pg_type'::regclass AND d.objid = t.oid
                                    AND d.deptype = 'e')
                """))
            .fetch(r -> new TypeRow(text(r, SCHEMA), text(r, NAME), text(r, "kind"))),
        dsl.resultQuery(
                query(
                    """
                SELECT n.nspname AS schema_name, p.oid::regprocedure::text AS object_name
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE /*NOT_SYSTEM*/
                  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                                  WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid
                                    AND d.deptype = 'e')
                """))
            .fetch(r -> new FunctionRow(text(r, SCHEMA), text(r, NAME))),
        viewDependencies(dsl),
        dsl.resultQuery("SELECT extname FROM pg_extension ORDER BY extname")
            .fetch(r -> text(r, "extname")),
        dsl.resultQuery("SELECT count(*) AS object_count FROM pg_largeobject_metadata")
            .fetchSingle(r -> r.get("object_count", Long.class)),
        role(dsl),
        schemaPrivileges(dsl),
        relationPrivileges(dsl));
  }

  private static List<RelationRow> relations(final DSLContext dsl) {
    return dsl.resultQuery(
            query(
                """
            SELECT n.nspname AS schema_name, c.relname AS object_name, c.relkind::text AS kind,
                   EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.oid AND i.indisprimary)
                     AS has_primary_key,
                   (SELECT p.relname FROM pg_inherits h JOIN pg_class p ON p.oid = h.inhparent
                    WHERE h.inhrelid = c.oid AND p.relkind <> 'p' LIMIT 1) AS inheritance_parent
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN /*RELATION_KINDS*/ AND /*NOT_SYSTEM*/
            """))
        .fetch(
            r ->
                new RelationRow(
                    text(r, SCHEMA),
                    text(r, NAME),
                    text(r, "kind"),
                    r.get("has_primary_key", Boolean.class),
                    r.get("inheritance_parent", String.class)));
  }

  private static List<ColumnRow> columns(final DSLContext dsl) {
    return dsl.resultQuery(
            query(
                """
            SELECT n.nspname AS schema_name, c.relname AS table_name, a.attname AS object_name,
                   format_type(a.atttypid, a.atttypmod) AS type_name, t.typtype::text AS type_kind,
                   a.attidentity::text AS identity_kind, a.attgenerated::text AS generated_kind,
                   pg_get_expr(d.adbin, d.adrelid) AS default_expression
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_type t ON t.oid = a.atttypid
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE c.relkind IN ('r', 'p') AND a.attnum > 0 AND NOT a.attisdropped AND /*NOT_SYSTEM*/
            """))
        .fetch(
            r ->
                new ColumnRow(
                    text(r, SCHEMA),
                    text(r, TABLE),
                    text(r, NAME),
                    text(r, "type_name"),
                    text(r, "type_kind"),
                    text(r, "identity_kind"),
                    text(r, "generated_kind"),
                    r.get("default_expression", String.class)));
  }

  private static List<IndexRow> indexes(final DSLContext dsl) {
    return dsl.resultQuery(
            query(
                """
            SELECT n.nspname AS schema_name, t.relname AS table_name, ic.relname AS object_name,
                   am.amname AS access_method, i.indisprimary AS is_primary,
                   i.indnkeyatts AS key_count, i.indnatts AS total_count,
                   i.indexprs IS NOT NULL AS has_expressions, i.indpred IS NOT NULL AS has_predicate,
                   EXISTS (SELECT 1 FROM pg_constraint x
                           WHERE x.conindid = i.indexrelid AND x.contype = 'x') AS backs_exclusion
            FROM pg_index i
            JOIN pg_class ic ON ic.oid = i.indexrelid
            JOIN pg_class t ON t.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            JOIN pg_am am ON am.oid = ic.relam
            WHERE /*NOT_SYSTEM*/
            """))
        .fetch(
            r ->
                new IndexRow(
                    text(r, SCHEMA),
                    text(r, TABLE),
                    text(r, NAME),
                    text(r, "access_method"),
                    r.get("is_primary", Boolean.class),
                    r.get("key_count", Integer.class),
                    r.get("total_count", Integer.class),
                    r.get("has_expressions", Boolean.class),
                    r.get("has_predicate", Boolean.class),
                    r.get("backs_exclusion", Boolean.class)));
  }

  private static List<ViewDependencyRow> viewDependencies(final DSLContext dsl) {
    return dsl.resultQuery(
            query(
                """
            SELECT DISTINCT n.nspname AS schema_name, v.relname AS object_name,
                   rn.nspname AS referenced_schema, r.relname AS referenced_view
            FROM pg_depend d
            JOIN pg_rewrite rw ON rw.oid = d.objid
            JOIN pg_class v ON v.oid = rw.ev_class
            JOIN pg_namespace n ON n.oid = v.relnamespace
            JOIN pg_class r ON r.oid = d.refobjid
            JOIN pg_namespace rn ON rn.oid = r.relnamespace
            WHERE d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass
              AND v.relkind IN ('v', 'm') AND r.relkind IN ('v', 'm') AND r.oid <> v.oid AND /*NOT_SYSTEM*/
            """))
        .fetch(
            r ->
                new ViewDependencyRow(
                    text(r, SCHEMA),
                    text(r, NAME),
                    text(r, "referenced_schema"),
                    text(r, "referenced_view")));
  }

  private static RoleRow role(final DSLContext dsl) {
    return dsl.resultQuery(
            """
            SELECT r.rolname AS object_name, r.rolsuper, r.rolcreatedb, r.rolcreaterole,
                   r.rolreplication, r.rolbypassrls,
                   has_database_privilege(current_database(), 'CREATE') AS can_create
            FROM pg_roles r
            WHERE r.rolname = current_user
            """)
        .fetchSingle(
            r ->
                new RoleRow(
                    text(r, NAME),
                    r.get("rolsuper", Boolean.class),
                    r.get("rolcreatedb", Boolean.class),
                    r.get("rolcreaterole", Boolean.class),
                    r.get("rolreplication", Boolean.class),
                    r.get("rolbypassrls", Boolean.class),
                    r.get("can_create", Boolean.class)));
  }

  private static List<SchemaPrivilegeRow> schemaPrivileges(final DSLContext dsl) {
    return dsl.resultQuery(
            query(
                """
            SELECT n.nspname AS schema_name,
                   has_schema_privilege(n.oid, 'USAGE') AS usage_granted,
                   has_schema_privilege(n.oid, 'CREATE') AS create_granted,
                   pg_get_userbyid(n.nspowner) = current_user AS owned_by_role
            FROM pg_namespace n
            WHERE /*NOT_SYSTEM*/
            """))
        .fetch(
            r ->
                new SchemaPrivilegeRow(
                    text(r, SCHEMA),
                    r.get("usage_granted", Boolean.class),
                    r.get("create_granted", Boolean.class),
                    r.get("owned_by_role", Boolean.class)));
  }

  private static List<RelationPrivilegeRow> relationPrivileges(final DSLContext dsl) {
    return dsl.resultQuery(
            query(
                """
            SELECT n.nspname AS schema_name, c.relname AS object_name,
                   has_table_privilege(c.oid, 'SELECT') OR has_table_privilege(c.oid, 'INSERT')
                     OR has_table_privilege(c.oid, 'UPDATE') OR has_table_privilege(c.oid, 'DELETE')
                     OR has_table_privilege(c.oid, 'TRUNCATE')
                     OR has_table_privilege(c.oid, 'REFERENCES')
                     OR has_table_privilege(c.oid, 'TRIGGER')
                     OR has_table_privilege(c.oid, 'MAINTAIN') AS any_granted,
                   has_table_privilege(c.oid, 'TRUNCATE') AS truncate_granted,
                   has_table_privilege(c.oid, 'REFERENCES') AS references_granted,
                   has_table_privilege(c.oid, 'TRIGGER') AS trigger_granted,
                   has_table_privilege(c.oid, 'MAINTAIN') AS maintain_granted,
                   pg_get_userbyid(c.relowner) = current_user AS owned_by_role
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN /*RELATION_KINDS*/ AND /*NOT_SYSTEM*/
            """))
        .fetch(
            r ->
                new RelationPrivilegeRow(
                    text(r, SCHEMA),
                    text(r, NAME),
                    r.get("any_granted", Boolean.class),
                    r.get("truncate_granted", Boolean.class),
                    r.get("references_granted", Boolean.class),
                    r.get("trigger_granted", Boolean.class),
                    r.get("maintain_granted", Boolean.class),
                    r.get("owned_by_role", Boolean.class)));
  }

  /** 問い合わせの雛形の{@code /*NOT_SYSTEM*\/}と{@code /*RELATION_KINDS*\/}を条件に置き換える。 */
  private static String query(final String template) {
    return template
        .replace("/*NOT_SYSTEM*/", NOT_SYSTEM)
        .replace("/*RELATION_KINDS*/", RELATION_KINDS);
  }

  private static String text(final Record row, final String column) {
    return row.get(column, String.class);
  }
}
