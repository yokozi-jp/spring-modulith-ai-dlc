package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ConstraintRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.IndexRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import com.example.demo.shared.persistence.DeclaredAggregate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 子の履歴の表の宣言を、DBのカタログと突き合わせる（ADR-131）。
 *
 * <p>{@code
 * ChildTable.of}は、履歴の表の列の名前とJavaの型だけを宣言のときに確かめる。長さとNULLの可否の食い違いは、最初の削除のINSERTが失敗して初めて分かるため、ここでカタログの型とNOT
 * NULLまで比べる。
 */
final class ArchiveDeclarationConventions {

  /** 履歴の表に、子のテーブルの列がないか、型かNULLの可否が違う。 */
  /* package */ static final String COLUMN_MISMATCH = "AG3";

  /** 履歴の表が、主キーを持たない、または一意制約、一意のインデックス、外部キーを持つ。 */
  /* package */ static final String WRONG_SHAPE = "AG4";

  /** 履歴の表（{@code h_}）が、どの子からも宣言されていない。 */
  /* package */ static final String ORPHAN = "AG5";

  /** 履歴の表の接頭辞。 */
  private static final String PREFIX = "h_";

  /** 履歴の表の規約の文書。 */
  private static final String DOC =
      "docs/adr/ADR-131-archive-removed-child-rows-in-table-writer.md";

  private ArchiveDeclarationConventions() {
    // 静的メソッドだけを持つ。
  }

  /**
   * 宣言した履歴の表を、カタログの列、制約、インデックスと突き合わせる。
   *
   * @param aggregates 宣言した集約
   * @param relations テーブルとビュー
   * @param columns カラム
   * @param constraints 制約
   * @param indexes インデックス
   * @return 違反
   */
  /* package */ static List<ConventionViolation> check(
      final List<DeclaredAggregate> aggregates,
      final List<RelationRow> relations,
      final List<ColumnRow> columns,
      final List<ConstraintRow> constraints,
      final List<IndexRow> indexes) {
    final List<DeclaredAggregate.DeclaredChild> children =
        aggregates.stream().flatMap(aggregate -> aggregate.children().stream()).toList();
    final Set<String> archives =
        children.stream().map(DeclaredAggregate.DeclaredChild::archive).collect(Collectors.toSet());
    return Stream.of(
            children.stream().flatMap(child -> columnMismatches(child, columns)),
            archives.stream()
                .flatMap(archive -> wrongShapes(archive, relations, constraints, indexes)),
            orphans(archives, relations))
        .flatMap(Function.identity())
        .toList();
  }

  private static Stream<ConventionViolation> columnMismatches(
      final DeclaredAggregate.DeclaredChild child, final List<ColumnRow> columns) {
    final Map<String, ColumnRow> archived =
        columns.stream()
            .filter(row -> qualified(row.schema(), row.table()).equals(child.archive()))
            .collect(Collectors.toMap(ColumnRow::column, Function.identity()));
    return columns.stream()
        .filter(row -> qualified(row.schema(), row.table()).equals(child.table()))
        .filter(row -> !matches(row, archived.get(row.column())))
        .map(row ->
            new ConventionViolation(
                COLUMN_MISMATCH,
                child.archive() + "." + row.column(),
                "履歴の表の列を、子のテーブルの列と同じ型とNULLの可否にする（" + DOC + "）。"));
  }

  private static boolean matches(final ColumnRow source, final ColumnRow archived) {
    return archived != null
        && source.typeName().equals(archived.typeName())
        && source.notNull() == archived.notNull();
  }

  private static Stream<ConventionViolation> wrongShapes(
      final String archive,
      final List<RelationRow> relations,
      final List<ConstraintRow> constraints,
      final List<IndexRow> indexes) {
    final boolean hasPrimaryKey =
        relations.stream()
            .anyMatch(row ->
                qualified(row.schema(), row.name()).equals(archive) && row.primaryKey() != null);
    final boolean hasUniqueIndex =
        indexes.stream().anyMatch(row -> isOf(archive, row) && row.unique() && !row.primary());
    final boolean hasUniqueOrForeignKey =
        constraints.stream()
            .anyMatch(row ->
                qualified(row.schema(), row.table()).equals(archive)
                    && ("u".equals(row.type()) || "f".equals(row.type())));
    return hasPrimaryKey && !hasUniqueIndex && !hasUniqueOrForeignKey
        ? Stream.empty()
        : Stream.of(
            new ConventionViolation(
                WRONG_SHAPE, archive, "履歴の表は、代理の主キーだけを持ち、一意制約と外部キーを持たない（" + DOC + "）。"));
  }

  private static boolean isOf(final String archive, final IndexRow row) {
    return qualified(row.schema(), row.table()).equals(archive);
  }

  private static Stream<ConventionViolation> orphans(
      final Set<String> archives, final List<RelationRow> relations) {
    return relations.stream()
        .filter(row -> row.isTable() && SchemaConventions.isBusinessSchema(row.schema()))
        .filter(row -> row.name().startsWith(PREFIX))
        .map(row -> qualified(row.schema(), row.name()))
        .filter(name -> !archives.contains(name))
        .map(name ->
            new ConventionViolation(
                ORPHAN, name, "履歴の表を、子の ChildTable.of の引数にするか、使わなければ削除する（" + DOC + "）。"));
  }

  private static String qualified(final String schema, final String table) {
    return schema + "." + table;
  }
}
