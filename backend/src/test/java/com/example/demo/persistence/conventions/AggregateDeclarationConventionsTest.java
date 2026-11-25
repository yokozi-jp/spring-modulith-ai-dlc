package com.example.demo.persistence.conventions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.persistence.conventions.CatalogSnapshot.ConstraintRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.IndexRow;
import com.example.demo.shared.persistence.DeclaredAggregate;
import com.example.demo.shared.persistence.DeclaredAggregate.DeclaredChild;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 集約の宣言と外部キーと一意索引の突き合わせ（{@link AggregateDeclarationConventions}）を、合成した宣言とカタログの行で検証する。DBに接続しない。 */
class AggregateDeclarationConventionsTest {

  /** 注文のルートのテーブル。 */
  private static final String ORDER = "ordering.t_order";

  /** テナントの列。 */
  private static final String TENANT = "tenant_public_id";

  /** 注文を一つに決める一意の列。 */
  private static final String PUBLIC_ID = "public_id";

  /** 注文の明細のテーブル。 */
  private static final String LINE = "ordering.t_order_line";

  /** 注文の明細の履歴の表。 */
  private static final String ARCHIVE = "ordering.h_order_line";

  /** 明細から注文への外部キー。テナントの列との複合である。 */
  private static final ConstraintRow LINE_TO_ORDER =
      new ConstraintRow(
          "ordering",
          "t_order_line",
          "fk_t_order_line",
          "f",
          "ordering",
          List.of(TENANT, "order_id"),
          "t_order",
          List.of(TENANT, "id"));

  /** 明細を注文の子として正しく宣言した集約。 */
  private static final DeclaredAggregate DECLARED =
      new DeclaredAggregate(
          ORDER, PUBLIC_ID, List.of(new DeclaredChild(LINE, "order_id", ARCHIVE)), List.of());

  /** 注文の、テナントと受付番号の一意索引。 */
  private static final IndexRow ORDER_NUMBER_INDEX =
      new IndexRow(
          "ordering",
          "t_order",
          "uk_2_t_order",
          "btree",
          false,
          2,
          2,
          false,
          false,
          false,
          true,
          TENANT,
          List.of(TENANT, "order_no"));

  private static List<String> keys(final List<ConventionViolation> violations) {
    return violations.stream().map(ConventionViolation::key).toList();
  }

  @Test
  @DisplayName("外部キーの参照元が子として宣言され、parentKey が外部キーの列にあれば通る")
  void acceptsMatchingDeclaration() {
    assertThat(
            AggregateDeclarationConventions.check(
                List.of(DECLARED), List.of(LINE_TO_ORDER), List.of(), List.of()))
        .isEmpty();
  }

  @Test
  @DisplayName("AG1：子を独立のルートとして宣言すると、外部キーの参照元が子でないとして拒否する")
  void rejectsChildDeclaredAsRoot() {
    final List<DeclaredAggregate> mutated =
        List.of(
            new DeclaredAggregate(ORDER, PUBLIC_ID, List.of(), List.of()),
            new DeclaredAggregate(LINE, "line_no", List.of(), List.of()));

    assertThat(
            keys(
                AggregateDeclarationConventions.check(
                    mutated, List.of(LINE_TO_ORDER), List.of(), List.of())))
        .containsExactly("AG1 ordering.t_order_line -> ordering.t_order");
  }

  @Test
  @DisplayName("AG2：parentKey が外部キーの列でなければ拒否する")
  void rejectsParentKeyOutsideForeignKey() {
    final List<DeclaredAggregate> mutated =
        List.of(
            new DeclaredAggregate(
                ORDER, PUBLIC_ID, List.of(new DeclaredChild(LINE, "line_no", ARCHIVE)), List.of()));

    assertThat(
            keys(
                AggregateDeclarationConventions.check(
                    mutated, List.of(LINE_TO_ORDER), List.of(), List.of())))
        .containsExactly("AG2 ordering.t_order_line.line_no");
  }

  @Test
  @DisplayName("許可リストは該当の違反だけを抑止し、外部キーがなくなった項目は違反として報告する")
  void allowlistSuppressesOnlyMatchingViolationAndReportsStaleEntry() {
    final AllowlistEntry entry =
        new AllowlistEntry("AG1", "ordering.t_order_line -> ordering.t_order", "試験用");
    final List<DeclaredAggregate> none = List.of();

    assertThat(
            AggregateDeclarationConventions.check(
                none, List.of(LINE_TO_ORDER), List.of(), List.of(entry)))
        .isEmpty();
    assertThat(
            keys(AggregateDeclarationConventions.check(none, List.of(), List.of(), List.of(entry))))
        .containsExactly("ALLOWLIST AG1 ordering.t_order_line -> ordering.t_order");
  }

  @Test
  @DisplayName("AG3、AG4：duplicateKey は、列の集合が一致する一意索引があれば通り、なければ、またはルートの一意の列を含めば拒否する")
  void checksDuplicateKeyAgainstUniqueIndexes() {
    final List<IndexRow> indexes = List.of(ORDER_NUMBER_INDEX);

    assertThat(
            keys(
                AggregateDeclarationConventions.check(
                    List.of(withDuplicateKey("order_no", TENANT)), List.of(), indexes, List.of())))
        .as("列の順は問わない")
        .isEmpty();
    assertThat(
            keys(
                AggregateDeclarationConventions.check(
                    List.of(withDuplicateKey("order_no")), List.of(), indexes, List.of())))
        .containsExactly("AG3 ordering.t_order(order_no)");
    assertThat(
            keys(
                AggregateDeclarationConventions.check(
                    List.of(withDuplicateKey(TENANT, PUBLIC_ID)), List.of(), indexes, List.of())))
        .containsExactly(
            "AG3 ordering.t_order(tenant_public_id, public_id)", "AG4 ordering.t_order.public_id");
  }

  private static DeclaredAggregate withDuplicateKey(final String... columns) {
    return new DeclaredAggregate(ORDER, PUBLIC_ID, List.of(), List.of(columns));
  }
}
