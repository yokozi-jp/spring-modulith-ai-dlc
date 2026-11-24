package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.CatalogSnapshot.ConstraintRow;
import com.example.demo.shared.persistence.DeclaredAggregate;
import com.example.demo.shared.persistence.DeclaredAggregate.DeclaredChild;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 集約の宣言を、DBの外部キーと突き合わせる。
 *
 * <p>子だけの更新が親の{@code lock_no}を進めるのは、{@code
 * TableWriter}が子を親の集約の中でだけ書くからである。子のテーブルを、親の子として宣言し忘れると、その子だけを更新する Repositoryは親の{@code
 * lock_no}を進めない（ADR-054）。同じモジュールの表どうしには外部キーを置く規約があるため、外部キーから親子の関係を読み、宣言と照らす。
 */
final class AggregateDeclarationConventions {

  /** 外部キーの参照元が、参照先をルートとする集約の子として宣言されていない（許可リストで認める）。 */
  /* package */ static final String UNDECLARED_CHILD = "AG1";

  /** 宣言した子の{@code parentKey}が、ルートへの外部キーの列に含まれない。 */
  /* package */ static final String WRONG_PARENT_KEY = "AG2";

  /** 集約の宣言の許可リスト。対象は{@code 参照する表 -> 参照される表}。 */
  /* package */ static final List<AllowlistEntry> ALLOWLIST =
      List.of(
          new AllowlistEntry(
              UNDECLARED_CHILD,
              "tenancy.m_membership -> tenancy.m_user",
              "所属はテナントの集約の子で、利用者は別の集約のルートである。所属を書く保存はテナントの lock_no を進め、利用者の lock_no は進めない。"));

  /** 集約の宣言の規約の文書。 */
  private static final String DOC =
      "docs/adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md";

  private AggregateDeclarationConventions() {
    // 静的メソッドだけを持つ。
  }

  /**
   * 宣言と外部キーを突き合わせ、許可リストを適用する。
   *
   * @param aggregates 宣言した集約
   * @param constraints カタログの制約。外部キー以外と、業務の外のスキーマは読み飛ばす
   * @param allowlist 許可リスト
   * @return 許可リストで抑止されなかった違反と、使われなかった許可リストの項目
   */
  /* package */ static List<ConventionViolation> check(
      final List<DeclaredAggregate> aggregates,
      final List<ConstraintRow> constraints,
      final List<AllowlistEntry> allowlist) {
    final List<ConstraintRow> foreignKeys =
        constraints.stream()
            .filter(
                row -> "f".equals(row.type()) && SchemaConventions.isBusinessSchema(row.schema()))
            .filter(row -> row.schema().equals(row.referencedSchema()))
            .toList();
    return AllowlistEntry.apply(
        Stream.concat(
                undeclaredChildren(aggregates, foreignKeys),
                wrongParentKeys(aggregates, foreignKeys))
            .toList(),
        allowlist);
  }

  private static Stream<ConventionViolation> undeclaredChildren(
      final List<DeclaredAggregate> aggregates, final List<ConstraintRow> foreignKeys) {
    final Set<String> declared =
        aggregates.stream()
            .flatMap(aggregate ->
                aggregate.children().stream()
                    .map(child -> child.table() + " -> " + aggregate.root()))
            .collect(java.util.stream.Collectors.toSet());
    return foreignKeys.stream()
        .map(row ->
            qualified(row.schema(), row.table())
                + " -> "
                + qualified(row.referencedSchema(), row.referencedTable()))
        .filter(edge -> !declared.contains(edge))
        .map(edge ->
            new ConventionViolation(
                UNDECLARED_CHILD,
                edge,
                "参照する表を、参照される表をルートとする集約の ChildTable として宣言する。別の集約への参照なら、理由を付けて許可リストに足す（"
                    + DOC
                    + "）。"));
  }

  private static Stream<ConventionViolation> wrongParentKeys(
      final List<DeclaredAggregate> aggregates, final List<ConstraintRow> foreignKeys) {
    return aggregates.stream()
        .flatMap(aggregate ->
            aggregate.children().stream()
                .filter(child -> !isForeignKeyColumn(aggregate, child, foreignKeys))
                .map(child ->
                    new ConventionViolation(
                        WRONG_PARENT_KEY,
                        child.table() + "." + child.parentKey(),
                        aggregate.root() + " への外部キーの列を ChildTable の parentKey にする（" + DOC + "）。")));
  }

  private static boolean isForeignKeyColumn(
      final DeclaredAggregate aggregate,
      final DeclaredChild child,
      final List<ConstraintRow> foreignKeys) {
    return foreignKeys.stream()
        .filter(row -> qualified(row.schema(), row.table()).equals(child.table()))
        .filter(row ->
            qualified(row.referencedSchema(), row.referencedTable()).equals(aggregate.root()))
        .anyMatch(row -> row.columns().contains(child.parentKey()));
  }

  private static String qualified(final String schema, final String table) {
    return schema + "." + table;
  }
}
