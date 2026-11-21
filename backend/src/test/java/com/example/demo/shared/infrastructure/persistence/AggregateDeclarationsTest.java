package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Repository が宣言した集約の形が、集約をまたいで衝突しないことを確かめる。 */
class AggregateDeclarationsTest {

  /** Repository のクラスを探す基底パッケージ。 */
  private static final String BASE_PACKAGE = "com.example.demo";

  // private な定数の宣言を読むため、可視性を変える。
  @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
  @Test
  @DisplayName("同じ子のテーブルを、二つの集約の子として宣言しない")
  void eachChildTableBelongsToOneAggregate() throws IllegalAccessException {
    final List<String> declared = new ArrayList<>();
    for (final Field field : aggregateTableFields()) {
      field.setAccessible(true);
      final AggregateTable<?, ?, ?> aggregate = (AggregateTable<?, ?, ?>) field.get(null);
      for (final ChildTable<?> child : aggregate.children()) {
        declared.add(child.table().getName());
      }
    }

    // 二つの集約が同じ子を書くと、一方の保存が他方の子の行を「なくなった子」として削除する（ADR-054）。
    assertThat(declared).doesNotHaveDuplicates().isNotEmpty();
  }

  /** Repository のクラスの、AggregateTable 型の static フィールドを集める。 */
  private static List<Field> aggregateTableFields() {
    final List<Field> fields = new ArrayList<>();
    for (final JavaClass javaClass : new ClassFileImporter().importPackages(BASE_PACKAGE)) {
      if (!javaClass.getSimpleName().startsWith("Jooq")
          || !javaClass.getSimpleName().endsWith("Repository")) {
        continue;
      }
      for (final Field field : javaClass.reflect().getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())
            && AggregateTable.class.isAssignableFrom(field.getType())) {
          fields.add(field);
        }
      }
    }
    return fields;
  }
}
