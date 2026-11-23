package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.LogicalName;
import com.example.demo.persistence.conventions.CatalogSnapshot.Origin;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import java.util.List;
import java.util.stream.Stream;

/**
 * 業務テーブルと、そのカラムの論理名のコメントの規則（N12）を、カタログの行から判定する。
 *
 * <p>DBに接続しない純粋な関数である。{@link SchemaConventions#isBusinessSchema(String)}で業務スキーマの行だけを判定する。
 *
 * <p>N12は、{@link SchemaTableConventions}のメソッドの数がPMDのTooManyMethodsの上限を超えないよう、別のファイルに置く。
 */
final class SchemaLogicalNameConventions {

  /** 論理名のコメントの書き方の節。 */
  private static final String REMARKS_DOC = "docs/database/migrations.md#論理名のコメント";

  /** 表のN12の違反の直し方。 */
  private static final String TABLE_DETAIL =
      "表に日本語の論理名のコメントを付ける。表を作るchangesetではcreateTableのremarksに書き、"
          + "既存の表にはsetTableRemarksのchangesetで足す（"
          + REMARKS_DOC
          + "）。";

  /** 列のN12の違反の直し方。 */
  private static final String COLUMN_DETAIL =
      "列に日本語の論理名のコメントを付ける。列を作るchangesetではcreateTableかaddColumnのcolumnのremarksに書き、"
          + "既存の列にはsetColumnRemarksのchangesetで足す（"
          + REMARKS_DOC
          + "）。";

  private SchemaLogicalNameConventions() {
    // 静的メソッドだけを持つ。
  }

  /**
   * N12：業務テーブルとそのカラムに、空白だけでないコメントがある。
   *
   * <p>ビューと、パーティションの子テーブルとそのカラムは対象外にする。PostgreSQLはパーティションの親のコメントを子へ写さない。
   *
   * @param relations テーブルとビュー
   * @param columns カラム
   * @return 違反
   */
  /* package */ static List<ConventionViolation> logicalNames(
      final List<RelationRow> relations, final List<ColumnRow> columns) {
    final Stream<ConventionViolation> tables =
        relations.stream()
            .filter(row -> SchemaConventions.isBusinessSchema(row.schema()))
            .filter(row -> row.isTable() && isMissing(row.logicalName()))
            .map(row ->
                new ConventionViolation("N12", row.schema() + "." + row.name(), TABLE_DETAIL));
    final Stream<ConventionViolation> tableColumns =
        columns.stream()
            .filter(row -> SchemaConventions.isBusinessSchema(row.schema()))
            .filter(row -> isMissing(row.logicalName()))
            .map(row ->
                new ConventionViolation(
                    "N12", row.schema() + "." + row.table() + "." + row.column(), COLUMN_DETAIL));
    return Stream.concat(tables, tableColumns).toList();
  }

  private static boolean isMissing(final LogicalName logicalName) {
    return logicalName.origin() == Origin.PLAIN && logicalName.comment().isBlank();
  }
}
