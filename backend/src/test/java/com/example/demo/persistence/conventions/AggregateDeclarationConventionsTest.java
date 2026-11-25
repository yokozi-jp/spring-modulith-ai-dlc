package com.example.demo.persistence.conventions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.persistence.conventions.CatalogSnapshot.ConstraintRow;
import com.example.demo.shared.persistence.DeclaredAggregate;
import com.example.demo.shared.persistence.DeclaredAggregate.DeclaredChild;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 集約の宣言と外部キーの突き合わせ（{@link AggregateDeclarationConventions}）を、合成した宣言と外部キーで検証する。DBに接続しない。 */
class AggregateDeclarationConventionsTest {

  /** 注文のルートのテーブル。 */
  private static final String ORDER = "ordering.t_order";

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
          List.of("tenant_public_id", "order_id"),
          "t_order",
          List.of("tenant_public_id", "id"));

  /** 明細を注文の子として正しく宣言した集約。 */
  private static final DeclaredAggregate DECLARED =
      new DeclaredAggregate(ORDER, List.of(new DeclaredChild(LINE, "order_id", ARCHIVE)));

  private static List<String> keys(final List<ConventionViolation> violations) {
    return violations.stream().map(ConventionViolation::key).toList();
  }

  @Test
  @DisplayName("外部キーの参照元が子として宣言され、parentKey が外部キーの列にあれば通る")
  void acceptsMatchingDeclaration() {
    assertThat(
            AggregateDeclarationConventions.check(
                List.of(DECLARED), List.of(LINE_TO_ORDER), List.of()))
        .isEmpty();
  }

  @Test
  @DisplayName("AG1：子を独立のルートとして宣言すると、外部キーの参照元が子でないとして拒否する")
  void rejectsChildDeclaredAsRoot() {
    final List<DeclaredAggregate> mutated =
        List.of(new DeclaredAggregate(ORDER, List.of()), new DeclaredAggregate(LINE, List.of()));

    assertThat(
            keys(AggregateDeclarationConventions.check(mutated, List.of(LINE_TO_ORDER), List.of())))
        .containsExactly("AG1 ordering.t_order_line -> ordering.t_order");
  }

  @Test
  @DisplayName("AG2：parentKey が外部キーの列でなければ拒否する")
  void rejectsParentKeyOutsideForeignKey() {
    final List<DeclaredAggregate> mutated =
        List.of(new DeclaredAggregate(ORDER, List.of(new DeclaredChild(LINE, "line_no", ARCHIVE))));

    assertThat(
            keys(AggregateDeclarationConventions.check(mutated, List.of(LINE_TO_ORDER), List.of())))
        .containsExactly("AG2 ordering.t_order_line.line_no");
  }

  @Test
  @DisplayName("許可リストは該当の違反だけを抑止し、外部キーがなくなった項目は違反として報告する")
  void allowlistSuppressesOnlyMatchingViolationAndReportsStaleEntry() {
    final AllowlistEntry entry =
        new AllowlistEntry("AG1", "ordering.t_order_line -> ordering.t_order", "試験用");
    final List<DeclaredAggregate> none = List.of();

    assertThat(AggregateDeclarationConventions.check(none, List.of(LINE_TO_ORDER), List.of(entry)))
        .isEmpty();
    assertThat(keys(AggregateDeclarationConventions.check(none, List.of(), List.of(entry))))
        .containsExactly("ALLOWLIST AG1 ordering.t_order_line -> ordering.t_order");
  }
}
