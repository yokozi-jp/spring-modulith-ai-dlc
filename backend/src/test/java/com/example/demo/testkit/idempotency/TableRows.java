package com.example.demo.testkit.idempotency;

import static com.example.demo.jooq.tenancy.Tables.M_TENANT;

import com.example.demo.jooq.DefaultCatalog;
import com.example.demo.jooq.modulith.Modulith;
import com.example.demo.shared.persistence.ModuleRoleTransactionManager;
import com.example.demo.shared.persistence.ModuleRoles;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.MigrationRoleConnections;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;

/**
 * 業務の表の中身。表とテナントの組ごとの行の一覧である。
 *
 * <p>所有者の接続で読む。業務の表は所有者にも RLS が効くため、{@code m_tenant} のテナントを 1 つずつ設定して読む。RLS の外の表（{@code
 * tenancy}）はテナントを設定せずに読み、{@code modulith} の表は読まない。表は jOOQ の生成コードから集める。
 *
 * @param slices 表とテナントの組 → 主キーの順の行
 */
/* package */ record TableRows(Map<Slice, List<Row>> slices) {

  /* package */ TableRows {
    slices = Map.copyOf(slices);
  }

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

  private static TableRows read(final Connection connection, final DSLContext owner) {
    final List<TenantId> tenants =
        owner
            .select(M_TENANT.PUBLIC_ID)
            .from(M_TENANT)
            .orderBy(M_TENANT.PUBLIC_ID)
            .fetch(M_TENANT.PUBLIC_ID)
            .stream()
            .map(TenantId::new)
            .toList();
    final List<Table<?>> tables =
        DefaultCatalog.DEFAULT_CATALOG.getSchemas().stream()
            .filter(schema -> !schema.equals(Modulith.MODULITH))
            .flatMap(schema -> schema.getTables().stream())
            .toList();
    final Stream<Map.Entry<Slice, List<Row>>> exempt =
        tables.stream()
            .filter(TableRows::rlsExempt)
            .map(table -> Map.entry(new Slice(table, new Scope.NoTenant()), fetch(owner, table)));
    // テナントの設定は接続の状態なので、テナントごとに読み切ってから次のテナントを設定する。
    final Stream<Map.Entry<Slice, List<Row>>> scoped =
        tenants.stream()
            .flatMap(tenant -> {
              setTenant(connection, tenant);
              return tables.stream()
                  .filter(table -> !rlsExempt(table))
                  .map(table ->
                      Map.entry(new Slice(table, new Scope.Tenant(tenant)), fetch(owner, table)))
                  .toList()
                  .stream();
            });
    return new TableRows(
        Stream.concat(exempt, scoped)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  private static boolean rlsExempt(final Table<?> table) {
    return ModuleRoles.RLS_EXEMPT_MODULES.contains(AsModule.moduleOf(table));
  }

  /** 所有者にも RLS が効くため、本番と同じ SQL でテナントをトランザクションの間だけ設定する。 */
  private static void setTenant(final Connection connection, final TenantId tenant) {
    try {
      ModuleRoleTransactionManager.setLocal(connection, null, tenant.value().toString());
    } catch (SQLException e) {
      throw new IllegalStateException("failed to set tenant " + tenant, e);
    }
  }

  private static List<Row> fetch(final DSLContext owner, final Table<?> table) {
    final UniqueKey<?> key = table.getPrimaryKey();
    final List<? extends Record> records =
        key == null
            ? owner.selectFrom(table).fetch()
            : owner.selectFrom(table).orderBy(key.getFields()).fetch();
    return records.stream().map(Row::from).toList();
  }

  /** {@code after} との差を、表とテナントごとに、消えた行と現れた行で書く。変わった行は両方に出る。差がなければ空。 */
  /* package */ Optional<String> diff(final TableRows after) {
    final String lines =
        Stream.concat(slices.keySet().stream(), after.slices.keySet().stream())
            .distinct()
            .sorted(Comparator.comparing(Slice::toString))
            .flatMap(slice ->
                describe(
                    slice,
                    slices.getOrDefault(slice, List.of()),
                    after.slices.getOrDefault(slice, List.of()))
                    .stream())
            .collect(Collectors.joining());
    return lines.isEmpty() ? Optional.empty() : Optional.of(lines);
  }

  /** 表とテナントの組の、消えた行と現れた行。差がなければ空。 */
  private static Optional<String> describe(
      final Slice slice, final List<Row> before, final List<Row> now) {
    final List<Row> removed = minus(before, now);
    final List<Row> added = minus(now, before);
    if (removed.isEmpty() && added.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        System.lineSeparator() + "  " + slice + ": removed=" + removed + " added=" + added);
  }

  /** {@code from} から {@code other} にある行を 1 つずつ除いた残り（多重集合の差）。 */
  private static List<Row> minus(final List<Row> from, final List<Row> other) {
    final List<Row> rest = new ArrayList<>(from);
    other.forEach(rest::remove);
    return rest;
  }

  /**
   * 表とテナントの組。
   *
   * @param schema 表のスキーマ
   * @param table 表の名前
   * @param scope 読んだテナント
   */
  /* package */ record Slice(String schema, String table, Scope scope) {

    private Slice(final Table<?> table, final Scope scope) {
      this(AsModule.moduleOf(table), table.getName(), scope);
    }

    @Override
    public String toString() {
      return schema + "." + table + " tenant=" + scope;
    }
  }

  /** 表を読んだテナント。 */
  /* package */ sealed interface Scope {

    /**
     * テナントを設定して読んだ。
     *
     * @param id テナント
     */
    record Tenant(TenantId id) implements Scope {
      @Override
      public String toString() {
        return id.value().toString();
      }
    }

    /** RLS の外の表で、テナントを設定せずに読んだ。 */
    record NoTenant() implements Scope {
      @Override
      public String toString() {
        return "(no tenant)";
      }
    }
  }

  /**
   * 表の 1 行。配列の値は中身の文字列にして比べる。配列の {@code equals} は参照を比べ、同じ中身の行を違うと判定するためである。
   *
   * @param columns 列の名前 → 値
   */
  /* package */ record Row(Map<String, @Nullable Object> columns) {

    private static Row from(final Record record) {
      final Map<String, @Nullable Object> columns = record.intoMap();
      columns.replaceAll((name, value) -> comparable(value));
      return new Row(columns);
    }

    private static @Nullable Object comparable(final @Nullable Object value) {
      return value != null && value.getClass().isArray()
          ? Arrays.deepToString(new Object[] {value})
          : value;
    }

    @Override
    public String toString() {
      return columns.toString();
    }
  }
}
