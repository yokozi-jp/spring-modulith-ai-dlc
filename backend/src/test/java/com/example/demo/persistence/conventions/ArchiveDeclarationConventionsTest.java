package com.example.demo.persistence.conventions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ConstraintRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.IndexRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import com.example.demo.shared.persistence.DeclaredAggregate;
import com.example.demo.shared.persistence.DeclaredAggregate.DeclaredChild;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 履歴の表の宣言とカタログの突き合わせ（{@link ArchiveDeclarationConventions}）を、合成したカタログで検証する。DBに接続しない。 */
class ArchiveDeclarationConventionsTest {

  /** 子のテーブルのスキーマ。 */
  private static final String SCHEMA = "ordering";

  /** 子のテーブルの名前。 */
  private static final String LINE_TABLE = "t_order_line";

  /** 列の名前。 */
  private static final String ROLE_COLUMN = "role_typ";

  /** 履歴の表の名前。 */
  private static final String ARCHIVE = "h_order_line";

  /** 履歴の表を宣言した集約。 */
  private static final List<DeclaredAggregate> DECLARED =
      List.of(
          new DeclaredAggregate(
              "ordering.t_order",
              "public_id",
              List.of(
                  new DeclaredChild("ordering.t_order_line", "order_id", "ordering." + ARCHIVE)),
              List.of()));

  /** 型の名前。 */
  private static final String VARCHAR_10 = "character varying(10)";

  private static ColumnRow column(
      final String table, final String name, final String type, final boolean notNull) {
    return new ColumnRow(SCHEMA, table, name, type, "b", "", "", null, notNull);
  }

  private static List<ColumnRow> matchingColumns() {
    return List.of(
        column(LINE_TABLE, ROLE_COLUMN, VARCHAR_10, true),
        column(ARCHIVE, ROLE_COLUMN, VARCHAR_10, true));
  }

  private static List<RelationRow> relations(final String... names) {
    return java.util.Arrays.stream(names)
        .map(name -> new RelationRow(SCHEMA, name, "r", name + "_id", null))
        .toList();
  }

  private static List<String> keys(final List<ConventionViolation> violations) {
    return violations.stream().map(ConventionViolation::key).toList();
  }

  @Test
  @DisplayName("履歴の表が、子の列と同じ型とNULLの可否を持ち、主キーだけを持てば通る")
  void acceptsMatchingArchive() {
    assertThat(
            ArchiveDeclarationConventions.check(
                DECLARED, relations(LINE_TABLE, ARCHIVE), matchingColumns(), List.of(), List.of()))
        .isEmpty();
  }

  @Test
  @DisplayName("AG3：履歴の表の列の長さかNULLの可否が子と違う、または列がなければ拒否する")
  void rejectsColumnMismatch() {
    final List<ColumnRow> columns =
        List.of(
            column(LINE_TABLE, ROLE_COLUMN, VARCHAR_10, true),
            column(LINE_TABLE, "note", VARCHAR_10, false),
            column(LINE_TABLE, "memo", VARCHAR_10, false),
            column(ARCHIVE, ROLE_COLUMN, "character varying(5)", true),
            column(ARCHIVE, "note", VARCHAR_10, true));

    assertThat(
            keys(
                ArchiveDeclarationConventions.check(
                    DECLARED, relations(LINE_TABLE, ARCHIVE), columns, List.of(), List.of())))
        .containsExactlyInAnyOrder(
            "AG3 ordering.h_order_line.role_typ",
            "AG3 ordering.h_order_line.note",
            "AG3 ordering.h_order_line.memo");
  }

  @Test
  @DisplayName("AG4：履歴の表に主キーがない、または一意制約、一意のインデックス、外部キーがあれば拒否する")
  void rejectsWrongShape() {
    final List<RelationRow> noPrimaryKey =
        List.of(new RelationRow(SCHEMA, ARCHIVE, "r", null, null));
    final ConstraintRow foreignKey =
        new ConstraintRow(SCHEMA, ARCHIVE, "fk_h_order_line", "f", SCHEMA);
    final IndexRow uniqueIndex =
        new IndexRow(
            SCHEMA,
            ARCHIVE,
            "uk_h_order_line",
            "btree",
            false,
            1,
            1,
            false,
            false,
            false,
            true,
            "order_id",
            List.of("order_id"));

    assertThat(keys(check(noPrimaryKey, List.of(), List.of())))
        .as("主キーがない")
        .containsExactly("AG4 ordering.h_order_line");
    assertThat(keys(check(relations(ARCHIVE), List.of(foreignKey), List.of())))
        .as("外部キー")
        .containsExactly("AG4 ordering.h_order_line");
    assertThat(keys(check(relations(ARCHIVE), List.of(), List.of(uniqueIndex))))
        .as("一意のインデックス")
        .containsExactly("AG4 ordering.h_order_line");
  }

  @Test
  @DisplayName("AG5：どの子からも宣言されていない履歴の表（h_）を拒否する")
  void rejectsOrphanArchive() {
    assertThat(
            keys(
                ArchiveDeclarationConventions.check(
                    DECLARED,
                    relations(LINE_TABLE, ARCHIVE, "h_unused"),
                    matchingColumns(),
                    List.of(),
                    List.of())))
        .containsExactly("AG5 ordering.h_unused");
  }

  private static List<ConventionViolation> check(
      final List<RelationRow> relations,
      final List<ConstraintRow> constraints,
      final List<IndexRow> indexes) {
    return ArchiveDeclarationConventions.check(
        DECLARED, relations, matchingColumns(), constraints, indexes);
  }
}
