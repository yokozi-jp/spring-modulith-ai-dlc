package com.example.demo.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 本番の Repository が宣言した集約の形が、集約をまたいで衝突しないことを確かめる。 */
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
}
