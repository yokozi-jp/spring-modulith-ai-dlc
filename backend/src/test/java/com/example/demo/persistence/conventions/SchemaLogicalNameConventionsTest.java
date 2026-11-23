package com.example.demo.persistence.conventions;

import static com.example.demo.persistence.conventions.CatalogRows.ITEM;
import static com.example.demo.persistence.conventions.CatalogRows.ITEM_ID;
import static com.example.demo.persistence.conventions.CatalogRows.MODULITH;
import static com.example.demo.persistence.conventions.CatalogRows.TABLE;
import static com.example.demo.persistence.conventions.CatalogRows.UUID;
import static com.example.demo.persistence.conventions.CatalogRows.columnRow;
import static com.example.demo.persistence.conventions.CatalogRows.keys;
import static com.example.demo.persistence.conventions.CatalogRows.tableRow;
import static com.example.demo.persistence.conventions.CatalogSnapshot.Origin.PARTITION_CHILD;
import static com.example.demo.persistence.conventions.CatalogSnapshot.Origin.PLAIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.LogicalName;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 表と列の論理名の規則（{@link SchemaLogicalNameConventions}）を、違反を含む行のデータで検証する。
 *
 * <p>DBに接続しない。各テストは違反の規則の記号と対象を、{@code 規則 対象}のキーの集合で確かめる。
 */
// 共有の行と定数（CatalogRows）を static import で読む。
@SuppressWarnings("PMD.TooManyStaticImports")
class SchemaLogicalNameConventionsTest {

  /** パーティションで分けた業務テーブル。 */
  private static final String SALE = "t_sale";

  /** {@link #SALE}の主キー。 */
  private static final String SALE_ID = "sale_id";

  /** 論理名のコメントの書き方の節。 */
  private static final String REMARKS_DOC = "docs/database/migrations.md#論理名のコメント";

  /** コメントのない表か列。 */
  private static final LogicalName EMPTY = new LogicalName("", PLAIN);

  /** 空白だけのコメントの表か列。 */
  private static final LogicalName BLANK = new LogicalName(" ", PLAIN);

  /** コメントのない、パーティションの子の表か列。 */
  private static final LogicalName CHILD = new LogicalName("", PARTITION_CHILD);

  @Test
  @DisplayName("N12：コメントがないか空白だけの業務テーブルとカラムを、表と列の直し方を添えて拒否する")
  void rejectsBusinessTablesAndColumnsWithoutComments() {
    final List<RelationRow> relations =
        List.of(
            tableRow(ITEM, TABLE, "r", ITEM_ID, EMPTY),
            tableRow(ITEM, "m_blank", "r", ITEM_ID, BLANK),
            tableRow(ITEM, "m_named", "r", ITEM_ID, new LogicalName("名前付き", PLAIN)),
            tableRow(ITEM, SALE, "p", SALE_ID, EMPTY),
            tableRow(ITEM, "t_stock", "p", ITEM_ID, BLANK),
            new RelationRow(ITEM, "v_m_item", "v", null, null),
            new RelationRow(MODULITH, "event_publication", "r", "id", null),
            new RelationRow("liquibase", "databasechangelog", "r", null, null));
    final List<ColumnRow> columns =
        List.of(
            columnRow(ITEM, TABLE, "item_code", EMPTY),
            columnRow(ITEM, TABLE, "item_name", BLANK),
            columnRow(ITEM, TABLE, ITEM_ID, new LogicalName("品目ID", PLAIN)),
            columnRow(ITEM, SALE, SALE_ID, EMPTY),
            new ColumnRow(MODULITH, "event_publication", "id", UUID, "b", "", "", null, true),
            new ColumnRow("liquibase", "databasechangelog", "id", "text", "b", "", "", null));
    final List<String> tableKeys =
        List.of("N12 item.m_item", "N12 item.m_blank", "N12 item.t_sale", "N12 item.t_stock");
    final List<String> columnKeys =
        List.of(
            "N12 item.m_item.item_code", "N12 item.m_item.item_name", "N12 item.t_sale.sale_id");

    final List<ConventionViolation> violations =
        SchemaLogicalNameConventions.logicalNames(relations, columns);

    assertThat(keys(violations))
        .containsExactlyInAnyOrderElementsOf(
            Stream.concat(tableKeys.stream(), columnKeys.stream()).toList());
    final Map<String, String> details =
        violations.stream()
            .collect(Collectors.toMap(ConventionViolation::key, ConventionViolation::detail));
    tableKeys.forEach(key ->
        assertThat(details)
            .hasEntrySatisfying(
                key,
                detail ->
                    assertThat(detail)
                        .contains("setTableRemarks", REMARKS_DOC)
                        .doesNotContain("setColumnRemarks")));
    columnKeys.forEach(key ->
        assertThat(details)
            .hasEntrySatisfying(
                key,
                detail ->
                    assertThat(detail)
                        .contains("setColumnRemarks", "addColumn", REMARKS_DOC)
                        .doesNotContain("setTableRemarks")));
  }

  @Test
  @DisplayName("N12：末端と中間のパーティションの子テーブルとそのカラムは、コメントがなくても拒否しない")
  void acceptsPartitionsWithoutComments() {
    final List<RelationRow> relations =
        List.of(
            tableRow(ITEM, SALE, "p", SALE_ID, new LogicalName("販売", PLAIN)),
            tableRow(ITEM, "t_sale_p2026", "p", SALE_ID, CHILD),
            tableRow(ITEM, "t_sale_p2026_10", "r", SALE_ID, CHILD));
    final List<ColumnRow> columns =
        List.of(
            columnRow(ITEM, SALE, SALE_ID, new LogicalName("販売ID", PLAIN)),
            columnRow(ITEM, "t_sale_p2026", SALE_ID, CHILD),
            columnRow(ITEM, "t_sale_p2026_10", SALE_ID, CHILD));

    assertThat(SchemaLogicalNameConventions.logicalNames(relations, columns)).isEmpty();
  }
}
