package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ConstraintRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.FunctionRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.IndexRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ModuleRoleRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.PolicyRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationPrivilegeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RoleRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.SchemaPrivilegeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.TriggerRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.TypeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ViewDependencyRow;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * 接続したロールで{@code pg_catalog}を読み、{@link CatalogSnapshot}を作る。
 *
 * <p>{@code information_schema}は、ロールに権限のないオブジェクトを見せない。GRANTを忘れたテーブルが検査から漏れないよう、{@code
 * pg_catalog}だけを読む。システムのスキーマを除き、{@code modulith}と{@code liquibase}は含めて読む。業務スキーマへの絞り込みは判定する側が行う。
 */
// pg_catalog の行ごとに読む問い合わせを 1 つずつ持つため、メソッドが多い。
@SuppressWarnings("PMD.TooManyMethods")
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
   * @param migrationRole changesetを適用したマイグレーションロールの名前
   * @return 読んだ行
   */
  /* package */ static CatalogSnapshot read(final DSLContext dsl, final String migrationRole) {
    return new CatalogSnapshot(
        relations(dsl),
        columns(dsl),
        dsl.resultQuery(
                query(
                    """
                    SELECT n.nspname AS schema_name, c.relname AS table_name, con.conname AS object_name,
                           con.contype::text AS kind, rn.nspname AS referenced_schema,
                           rc.relname AS referenced_table,
                           ARRAY(SELECT a.attname::text FROM unnest(con.conkey) WITH ORDINALITY k(attnum, ord)
                                 JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum
                                 ORDER BY k.ord) AS column_names,
                           ARRAY(SELECT a.attname::text FROM unnest(con.confkey) WITH ORDINALITY k(attnum, ord)
                                 JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum
                                 ORDER BY k.ord) AS referenced_column_names
                    FROM pg_constraint con
                    JOIN pg_class c ON c.oid = con.conrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    LEFT JOIN pg_class rc ON rc.oid = con.confrelid
                    LEFT JOIN pg_namespace rn ON rn.oid = rc.relnamespace
                    WHERE /*NOT_SYSTEM*/
                    """))
            .fetch(r ->
                new ConstraintRow(
                    text(r, SCHEMA),
                    text(r, TABLE),
                    text(r, NAME),
                    text(r, "kind"),
                    r.get("referenced_schema", String.class),
                    texts(r, "column_names"),
                    r.get("referenced_table", String.class),
                    texts(r, "referenced_column_names"))),
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
        role(dsl, dsl.fetchValue(DSL.currentUser())),
        role(dsl, migrationRole),
        schemaPrivileges(dsl),
        relationPrivileges(dsl),
        moduleRoles(dsl),
        policies(dsl));
  }

  private static List<PolicyRow> policies(final DSLContext dsl) {
    return dsl.resultQuery(
            """
            SELECT schemaname AS schema_name, tablename AS table_name, policyname AS object_name,
                   permissive, roles::text[] AS role_names, cmd, qual, with_check
            FROM pg_policies
            """)
        .fetch(r ->
            new PolicyRow(
                text(r, SCHEMA),
                text(r, TABLE),
                text(r, NAME),
                text(r, "permissive"),
                texts(r, "role_names"),
                text(r, "cmd"),
                r.get("qual", String.class),
                r.get("with_check", String.class)));
  }

  private static List<RelationRow> relations(final DSLContext dsl) {
    return dsl.resultQuery(
            query(
                """
                SELECT n.nspname AS schema_name, c.relname AS object_name, c.relkind::text AS kind,
                       (SELECT a.attname FROM pg_index i
                        JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                        WHERE i.indrelid = c.oid AND i.indisprimary) AS primary_key,
                       (SELECT p.relname FROM pg_inherits h JOIN pg_class p ON p.oid = h.inhparent
                        WHERE h.inhrelid = c.oid AND p.relkind <> 'p' LIMIT 1) AS inheritance_parent,
                       c.relrowsecurity AS row_security, c.relforcerowsecurity AS force_row_security,
                       coalesce(obj_description(c.oid, 'pg_class'), '') AS object_comment
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN /*RELATION_KINDS*/ AND /*NOT_SYSTEM*/
                """))
        .fetch(r ->
            new RelationRow(
                text(r, SCHEMA),
                text(r, NAME),
                text(r, "kind"),
                r.get("primary_key", String.class),
                r.get("inheritance_parent", String.class),
                r.get("row_security", Boolean.class),
                r.get("force_row_security", Boolean.class),
                text(r, "object_comment")));
  }

  private static List<ColumnRow> columns(final DSLContext dsl) {
    return dsl.resultQuery(
            query(
                """
                SELECT n.nspname AS schema_name, c.relname AS table_name, a.attname AS object_name,
                       format_type(a.atttypid, a.atttypmod) AS type_name, t.typtype::text AS type_kind,
                       a.attidentity::text AS identity_kind, a.attgenerated::text AS generated_kind,
                       pg_get_expr(d.adbin, d.adrelid) AS default_expression, a.attnotnull AS not_null,
                       coalesce(col_description(a.attrelid, a.attnum), '') AS object_comment
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_type t ON t.oid = a.atttypid
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE c.relkind IN ('r', 'p') AND a.attnum > 0 AND NOT a.attisdropped AND /*NOT_SYSTEM*/
                """))
        .fetch(r ->
            new ColumnRow(
                text(r, SCHEMA),
                text(r, TABLE),
                text(r, NAME),
                text(r, "type_name"),
                text(r, "type_kind"),
                text(r, "identity_kind"),
                text(r, "generated_kind"),
                r.get("default_expression", String.class),
                r.get("not_null", Boolean.class),
                text(r, "object_comment")));
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
                               WHERE x.conindid = i.indexrelid AND x.contype = 'x') AS backs_exclusion,
                       i.indisunique AS is_unique,
                       (SELECT a.attname FROM pg_attribute a
                        WHERE a.attrelid = i.indrelid AND a.attnum = i.indkey[0]) AS first_column
                FROM pg_index i
                JOIN pg_class ic ON ic.oid = i.indexrelid
                JOIN pg_class t ON t.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_am am ON am.oid = ic.relam
                WHERE /*NOT_SYSTEM*/
                """))
        .fetch(r ->
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
                r.get("backs_exclusion", Boolean.class),
                r.get("is_unique", Boolean.class),
                r.get("first_column", String.class)));
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
        .fetch(r ->
            new ViewDependencyRow(
                text(r, SCHEMA),
                text(r, NAME),
                text(r, "referenced_schema"),
                text(r, "referenced_view")));
  }

  /**
   * ロールの属性を読む。
   *
   * @param dsl jOOQのコンテキスト。{@code pg_roles}は誰でも読めるため、どのロールで接続してもよい
   * @param name ロール名
   * @return ロールの属性
   */
  private static RoleRow role(final DSLContext dsl, final String name) {
    return dsl.resultQuery(
            """
            SELECT r.rolname AS object_name, r.rolsuper, r.rolcreatedb, r.rolcreaterole,
                   r.rolreplication, r.rolbypassrls,
                   has_database_privilege(r.oid, current_database(), 'CREATE') AS can_create
            FROM pg_roles r
            WHERE r.rolname = ?
            """,
            name)
        .fetchSingle(r ->
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
        .fetch(r ->
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
        .fetch(r ->
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

  /**
   * 名前が{@code _module}で終わるロールを、属性、アプリロールとの関係、権限とともに読む。
   *
   * <p>権限は、ロールが{@code has_*_privilege}で使える権限をすべて読む。{@code
   * PUBLIC}への付与も含むため、モジュールのロールに余計な権限が付く経路を漏らさない。
   */
  private static List<ModuleRoleRow> moduleRoles(final DSLContext dsl) {
    final Map<String, Set<String>> privileges = new ConcurrentHashMap<>();
    dsl.resultQuery(
            query(
                """
                SELECT r.rolname AS role_name, n.nspname AS schema_name,
                       has_schema_privilege(r.oid, n.oid, 'USAGE') AS usage_granted,
                       has_schema_privilege(r.oid, n.oid, 'CREATE') AS create_granted
                FROM pg_roles r CROSS JOIN pg_namespace n
                WHERE r.rolname LIKE '%\\_module' AND n.nspname <> 'public' AND /*NOT_SYSTEM*/
                """))
        .forEach(r -> {
          final Set<String> granted =
              privileges.computeIfAbsent(text(r, "role_name"), k -> new HashSet<>());
          if (r.get("usage_granted", Boolean.class)) {
            granted.add("USAGE:" + text(r, SCHEMA));
          }
          if (r.get("create_granted", Boolean.class)) {
            granted.add("CREATE:" + text(r, SCHEMA));
          }
        });
    dsl.resultQuery(
            query(
                """
                SELECT r.rolname AS role_name, n.nspname AS schema_name, c.relname AS object_name, p.privilege
                FROM pg_roles r
                CROSS JOIN pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'),
                                   ('REFERENCES'), ('TRIGGER'), ('MAINTAIN')) AS p(privilege)
                WHERE r.rolname LIKE '%\\_module' AND c.relkind IN /*RELATION_KINDS*/ AND /*NOT_SYSTEM*/
                  AND has_table_privilege(r.oid, c.oid, p.privilege)
                """))
        .forEach(r ->
            privileges
                .computeIfAbsent(text(r, "role_name"), k -> new HashSet<>())
                .add(text(r, "privilege") + ":" + text(r, SCHEMA) + "." + text(r, NAME)));
    return dsl.resultQuery(
            """
            SELECT r.rolname AS object_name, r.rolsuper, r.rolcreatedb, r.rolcreaterole,
                   r.rolreplication, r.rolbypassrls, r.rolcanlogin,
                   has_database_privilege(r.oid, current_database(), 'CREATE') AS can_create,
                   COALESCE(m.set_option, false) AS app_set,
                   COALESCE(m.inherit_option, false) AS app_inherit
            FROM pg_roles r
            LEFT JOIN pg_auth_members m
              ON m.roleid = r.oid AND m.member = (SELECT oid FROM pg_roles WHERE rolname = current_user)
            WHERE r.rolname LIKE '%\\_module'
            ORDER BY r.rolname
            """)
        .fetch(r ->
            new ModuleRoleRow(
                new RoleRow(
                    text(r, NAME),
                    r.get("rolsuper", Boolean.class),
                    r.get("rolcreatedb", Boolean.class),
                    r.get("rolcreaterole", Boolean.class),
                    r.get("rolreplication", Boolean.class),
                    r.get("rolbypassrls", Boolean.class),
                    r.get("can_create", Boolean.class)),
                r.get("rolcanlogin", Boolean.class),
                r.get("app_set", Boolean.class),
                r.get("app_inherit", Boolean.class),
                privileges.getOrDefault(text(r, NAME), Set.of())));
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

  private static List<String> texts(final Record row, final String column) {
    return List.of(row.get(column, String[].class));
  }
}
