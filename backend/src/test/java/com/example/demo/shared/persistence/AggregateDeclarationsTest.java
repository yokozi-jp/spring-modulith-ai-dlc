package com.example.demo.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 本番の Repository が宣言した集約の形が、集約をまたいで衝突せず、宣言に合う add を持つことを確かめる。 */
class AggregateDeclarationsTest {

  @Test
  @DisplayName("同じ子のテーブルを二つの集約の子として宣言せず、子のテーブルを別の集約のルートにもしない")
  void eachTableBelongsToOneAggregateRole() {
    final List<AggregateTable<?, ?, ?>> aggregates = AggregateDeclarations.all();
    final List<String> children = new ArrayList<>();
    final List<String> roots = new ArrayList<>();
    for (final AggregateTable<?, ?, ?> aggregate : aggregates) {
      roots.add(aggregate.table().getName());
      for (final ChildTable<?> child : aggregate.children()) {
        children.add(child.table().getName());
      }
    }

    // 二つの集約が同じ子を書くと、一方の保存が他方の子の行を「なくなった子」として削除する（ADR-054）。
    assertThat(aggregates).isNotEmpty();
    assertThat(children).as("子のテーブル").doesNotHaveDuplicates();
    assertThat(roots).as("ルートのテーブル").doesNotHaveDuplicates().doesNotContainAnyElementsOf(children);
  }

  @Test
  @DisplayName("重複を許す列を宣言した集約を持つクラスの add は、insert が入れたかを boolean で返す")
  void idempotentAggregateOwnersReturnBooleanFromAdd() {
    final List<Class<?>> owners =
        AggregateDeclarations.byOwner().entrySet().stream()
            .filter(entry ->
                entry.getValue().stream()
                    .anyMatch(aggregate -> !aggregate.duplicateKey().isEmpty()))
            .map(Map.Entry::getKey)
            .toList();

    // 戻り値を捨てると、重複として入れなかった保存を、呼び出し側が入れたものとして続ける（ADR-129）。
    assertThat(owners).as("重複を許す列を宣言したクラス").isNotEmpty();
    assertThat(owners)
        .allSatisfy(owner ->
            assertThat(
                    Arrays.stream(owner.getDeclaredMethods())
                        .filter(method -> "add".equals(method.getName()) && !method.isSynthetic()))
                .as("%s の add", owner.getName())
                .isNotEmpty()
                .allSatisfy(method -> assertThat(method.getReturnType()).isEqualTo(boolean.class)));
  }
}
