package com.example.demo.persistence.conventions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.persistence.AggregateDeclarations;
import com.example.demo.shared.persistence.DeclaredAggregate;
import com.example.demo.shared.persistence.DeclaredAggregate.DeclaredChild;
import com.example.demo.testkit.DatabaseTest;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** 本番の集約の宣言を、マイグレーション済みのテストDBの外部キーと一意索引と突き合わせる。 */
@DatabaseTest
class AggregateDeclarationInspectionTest {

  /** アプリロールで接続したjOOQのコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** changesetを適用したマイグレーションロールの名前。 */
  @Value("${MIGRATION_DB_USERNAME}")
  private String migrationRole;

  private List<ConventionViolation> check(final List<DeclaredAggregate> aggregates) {
    final CatalogSnapshot catalog = SchemaCatalog.read(dsl, migrationRole);
    return AggregateDeclarationConventions.check(
        aggregates,
        catalog.constraints(),
        catalog.indexes(),
        AggregateDeclarationConventions.ALLOWLIST);
  }

  @Test
  @DisplayName("本番の集約の宣言は、外部キーと一意索引と一致する")
  void declaredAggregatesMatchForeignKeys() {
    final List<ConventionViolation> violations = check(AggregateDeclarations.declared());

    assertThat(violations).as("集約の宣言の違反: %s", violations).isEmpty();
  }

  @Test
  @DisplayName("本番の子の履歴の表は、子の列と同じ型とNULLの可否を持ち、一意制約と外部キーがなく、使われない履歴の表がない")
  void declaredArchivesMatchCatalog() {
    final CatalogSnapshot catalog = SchemaCatalog.read(dsl, migrationRole);

    final List<ConventionViolation> violations =
        ArchiveDeclarationConventions.check(
            AggregateDeclarations.declared(),
            catalog.relations(),
            catalog.columns(),
            catalog.constraints(),
            catalog.indexes());

    assertThat(violations).as("履歴の表の違反: %s", violations).isEmpty();
  }

  @Test
  @DisplayName("t_order_line を ORDER の子から外して独立のルートにすると、拒否する")
  void rejectsOrderLineDeclaredAsRoot() {
    final List<DeclaredAggregate> mutated = new ArrayList<>();
    for (final DeclaredAggregate aggregate : AggregateDeclarations.declared()) {
      mutated.add(
          new DeclaredAggregate(
              aggregate.root(),
              aggregate.key(),
              aggregate.children().stream()
                  .filter(child -> !"ordering.t_order_line".equals(child.table()))
                  .toList(),
              aggregate.duplicateKey()));
    }
    mutated.add(new DeclaredAggregate("ordering.t_order_line", "line_no", List.of(), List.of()));

    assertThat(check(mutated))
        .extracting(ConventionViolation::key)
        .containsExactly("AG1 ordering.t_order_line -> ordering.t_order");
  }

  @Test
  @DisplayName("m_membership の parentKey を user_id にすると、拒否する")
  void rejectsMembershipParentKeyOutsideTenantForeignKey() {
    final List<DeclaredAggregate> mutated =
        AggregateDeclarations.declared().stream()
            .map(aggregate ->
                new DeclaredAggregate(
                    aggregate.root(),
                    aggregate.key(),
                    aggregate.children().stream()
                        .map(child ->
                            "tenancy.m_membership".equals(child.table())
                                ? new DeclaredChild(child.table(), "user_id", child.archive())
                                : child)
                        .toList(),
                    aggregate.duplicateKey()))
            .toList();

    assertThat(check(mutated))
        .extracting(ConventionViolation::key)
        .containsExactly("AG2 tenancy.m_membership.user_id");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "order_public_id | AG3 payment.t_payment(order_public_id)",
        "tenant_public_id,public_id | AG4 payment.t_payment.public_id"
      })
  @DisplayName("t_payment の duplicateKey を一意索引とずらすと、拒否する")
  void rejectsPaymentDuplicateKeyOutsideItsUniqueIndex(
      final String columns, final String expected) {
    final List<DeclaredAggregate> mutated =
        AggregateDeclarations.declared().stream()
            .map(aggregate ->
                "payment.t_payment".equals(aggregate.root())
                    ? new DeclaredAggregate(
                        aggregate.root(),
                        aggregate.key(),
                        aggregate.children(),
                        List.of(columns.split(",")))
                    : aggregate)
            .toList();

    assertThat(check(mutated)).extracting(ConventionViolation::key).containsExactly(expected);
  }
}
