package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import java.util.List;
import java.util.stream.Stream;

/**
 * 業務テーブルと、そのカラムの論理名のコメントの規則（N12）を、カタログの行から判定する。
 *
 * <p>DBに接続しない純粋な関数である。{@link SchemaConventions#isBusinessSchema(String)}で業務スキーマの行だけを判定する。
 */
final class SchemaLogicalNameConventions {

  /** N12の違反の直し方。 */
  private static final String LOGICAL_NAME_DETAIL =
      "日本語の論理名をコメントで付ける。表と列を作るchangesetではcreateTableとaddColumnのremarksに書き、"
          + "既存の表と列にはsetTableRemarksとsetColumnRemarksのchangesetで足す（docs/database/postgresql-naming.md）。";

  private SchemaLogicalNameConventions() {
    // 静的メソッドだけを持つ。
  }

  /**
   * N12：業務テーブルとそのカラムに、論理名のコメントがある。ビューは対象外にする。
   *
   * @param relations テーブルとビュー
   * @param columns カラム
   * @return 違反
   */
  /* package */ static List<ConventionViolation> logicalNames(
      final List<RelationRow> relations, final List<ColumnRow> columns) {
    final Stream<String> tables =
        relations.stream()
            .filter(row -> SchemaConventions.isBusinessSchema(row.schema()))
            .filter(row -> row.isTable() && row.comment().isBlank())
            .map(row -> row.schema() + "." + row.name());
    final Stream<String> tableColumns =
        columns.stream()
            .filter(row -> SchemaConventions.isBusinessSchema(row.schema()))
            .filter(row -> row.comment().isBlank())
            .map(row -> row.schema() + "." + row.table() + "." + row.column());
    return Stream.concat(tables, tableColumns)
        .map(target -> new ConventionViolation("N12", target, LOGICAL_NAME_DETAIL))
        .toList();
  }
}
