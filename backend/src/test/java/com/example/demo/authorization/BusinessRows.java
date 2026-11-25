package com.example.demo.authorization;

import com.example.demo.jooq.DefaultCatalog;
import com.example.demo.jooq.modulith.Modulith;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.testkit.AsModule;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.Schema;
import org.jooq.Table;

/** 業務のスキーマのすべての表の行を、比べられる文字列で読む。認可の表のテストが、拒否した書き込みで行が変わらないことを確かめる。 */
final class BusinessRows {

  private BusinessRows() {}

  /**
   * テナントごとに、業務のスキーマ（{@code modulith} 以外）の全行を、モジュールのロールで読む。
   *
   * <p>業務の表は RLS で設定したテナントの行だけが見えるため、テナントごとに読む（ADR-107）。RLS の外の {@code tenancy} の表は、テナントの数だけ同じ行が入る。
   */
  /* package */ static List<String> read(final DSLContext dsl, final UUID... tenants) {
    return Stream.of(tenants)
        .map(TenantId::new)
        .flatMap(tenant ->
            DefaultCatalog.DEFAULT_CATALOG.getSchemas().stream()
                .filter(schema -> !Modulith.MODULITH.equals(schema))
                .flatMap(schema -> rows(dsl, schema, tenant).stream()))
        .sorted()
        .toList();
  }

  private static List<String> rows(
      final DSLContext dsl, final Schema schema, final TenantId tenant) {
    return AsModule.call(
        dsl,
        schema.getName(),
        tenant,
        tx -> schema.getTables().stream().flatMap(table -> rowsOf(tx, table)).toList());
  }

  private static Stream<String> rowsOf(final DSLContext tx, final Table<?> table) {
    return tx.selectFrom(table).fetch().stream()
        .map(row -> table.getQualifiedName() + "=" + row.intoList());
  }
}
