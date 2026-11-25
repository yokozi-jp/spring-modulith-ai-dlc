package com.example.demo.testkit.idempotency;

import static com.example.demo.jooq.tenancy.Tables.M_TENANT;

import com.example.demo.jooq.DefaultCatalog;
import com.example.demo.jooq.modulith.Modulith;
import com.example.demo.shared.persistence.ModuleRoleTransactionManager;
import com.example.demo.shared.persistence.ModuleRoles;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.MigrationRoleConnections;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.springframework.core.env.Environment;

/**
 * 業務の表の中身。表の名前 → テナント → 行の一覧である。
 *
 * <p>所有者の接続で読む。業務の表は所有者にも RLS が効くため、{@code m_tenant} のテナントを 1 つずつ設定して読む。RLS の外の表（{@code
 * tenancy}）はテナントを設定せずに読み、{@code modulith} の表は読まない。表は jOOQ の生成コードから集める。
 *
 * @param rows {@code <スキーマ>.<表>} → テナント（RLS の外の表は {@link #NO_TENANT}）→ 主キーの順の行
 */
/* package */ record TableRows(Map<String, Map<String, List<Map<String, Object>>>> rows) {

  /** RLS の外の表のテナントの欄。 */
  private static final String NO_TENANT = "(no tenant)";

  /** 所有者の接続で、いまコミットされている業務の表の中身を読む。 */
  /* package */ static TableRows read(final Environment environment) {
    try (Connection connection = MigrationRoleConnections.open(environment)) {
      connection.setAutoCommit(false);
      try {
        return read(connection, DSL.using(connection, SQLDialect.POSTGRES));
      } finally {
        connection.rollback();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to read business tables", e);
    }
  }

  // 1 スレッドで作って読むだけなので、並行の Map は要らない。
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private static TableRows read(final Connection connection, final DSLContext owner) {
    final List<String> tenants =
        owner
            .select(M_TENANT.PUBLIC_ID)
            .from(M_TENANT)
            .orderBy(M_TENANT.PUBLIC_ID)
            .fetch(M_TENANT.PUBLIC_ID)
            .stream()
            .map(UUID::toString)
            .toList();
    final List<Table<?>> tables =
        DefaultCatalog.DEFAULT_CATALOG.getSchemas().stream()
            .filter(schema -> !schema.equals(Modulith.MODULITH))
            .flatMap(schema -> schema.getTables().stream())
            .toList();
    final Map<String, Map<String, List<Map<String, Object>>>> rows = new TreeMap<>();
    tables.stream()
        .filter(TableRows::rlsExempt)
        .forEach(table ->
            rows.put(nameOf(table), new TreeMap<>(Map.of(NO_TENANT, fetch(owner, table)))));
    tenants.forEach(tenant -> {
      setTenant(connection, tenant);
      tables.stream()
          .filter(table -> !rlsExempt(table))
          .forEach(table ->
              rows.computeIfAbsent(nameOf(table), name -> new TreeMap<>())
                  .put(tenant, fetch(owner, table)));
    });
    return new TableRows(rows);
  }

  private static boolean rlsExempt(final Table<?> table) {
    return ModuleRoles.RLS_EXEMPT_MODULES.contains(AsModule.moduleOf(table));
  }

  private static String nameOf(final Table<?> table) {
    return AsModule.moduleOf(table) + "." + table.getName();
  }

  /** 所有者にも RLS が効くため、本番と同じ SQL でテナントをトランザクションの間だけ設定する。 */
  private static void setTenant(final Connection connection, final String tenant) {
    try {
      ModuleRoleTransactionManager.setLocal(connection, null, tenant);
    } catch (SQLException e) {
      throw new IllegalStateException("failed to set tenant " + tenant, e);
    }
  }

  private static List<Map<String, Object>> fetch(final DSLContext owner, final Table<?> table) {
    final UniqueKey<?> key = table.getPrimaryKey();
    final List<? extends Record> records =
        key == null
            ? owner.selectFrom(table).fetch()
            : owner.selectFrom(table).orderBy(key.getFields()).fetch();
    return records.stream().map(Record::intoMap).toList();
  }

  /** {@code after} との差を、表とテナントごとに、消えた行と現れた行で書く。変わった行は両方に出る。差がなければ空文字列。 */
  /* package */ String diff(final TableRows after) {
    return Stream.concat(rows.keySet().stream(), after.rows.keySet().stream())
        .distinct()
        .flatMap(table -> {
          final Map<String, List<Map<String, Object>>> before = rows.getOrDefault(table, Map.of());
          final Map<String, List<Map<String, Object>>> now =
              after.rows.getOrDefault(table, Map.of());
          final Set<String> tenants = new TreeSet<>(before.keySet());
          tenants.addAll(now.keySet());
          return tenants.stream()
              .map(tenant ->
                  describe(
                      table,
                      tenant,
                      before.getOrDefault(tenant, List.of()),
                      now.getOrDefault(tenant, List.of())));
        })
        .filter(line -> !line.isEmpty())
        .collect(Collectors.joining());
  }

  /** 表とテナントの、消えた行と現れた行。差がなければ空文字列。 */
  private static String describe(
      final String table,
      final String tenant,
      final List<Map<String, Object>> before,
      final List<Map<String, Object>> now) {
    final List<Map<String, Object>> removed = minus(before, now);
    final List<Map<String, Object>> added = minus(now, before);
    if (removed.isEmpty() && added.isEmpty()) {
      return "";
    }
    return System.lineSeparator()
        + "  "
        + table
        + " tenant="
        + tenant
        + ": removed="
        + removed
        + " added="
        + added;
  }

  /** {@code from} から {@code other} にある行を 1 つずつ除いた残り（多重集合の差）。 */
  private static List<Map<String, Object>> minus(
      final List<Map<String, Object>> from, final List<Map<String, Object>> other) {
    final List<Map<String, Object>> rest = new ArrayList<>(from);
    other.forEach(rest::remove);
    return rest;
  }
}
