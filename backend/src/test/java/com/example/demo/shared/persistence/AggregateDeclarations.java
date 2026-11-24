package com.example.demo.shared.persistence;

import com.example.demo.DemoApplication;
import com.example.demo.architecture.ProductionCodeOnly;
import com.example.demo.shared.persistence.DeclaredAggregate.DeclaredChild;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.jooq.Table;

/** 本番のクラスが宣言した集約を、テストから読む。 */
public final class AggregateDeclarations {

  private AggregateDeclarations() {
    // 静的メソッドだけを持つ。
  }

  /** 本番のクラスの、AggregateTable 型の static フィールドの値を集める。 */
  // private な定数の宣言を読むため、可視性を変える。
  @SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "unchecked"})
  /* package */ static List<AggregateTable<?, ?, ?>> all() {
    final List<AggregateTable<?, ?, ?>> aggregates = new ArrayList<>();
    for (final JavaClass javaClass :
        new ClassFileImporter()
            .withImportOption(new ProductionCodeOnly())
            .importPackagesOf(DemoApplication.class)) {
      for (final Field field : javaClass.reflect().getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())
            && AggregateTable.class.isAssignableFrom(field.getType())) {
          field.setAccessible(true);
          try {
            aggregates.add((AggregateTable<?, ?, ?>) field.get(null));
          } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read " + field, e);
          }
        }
      }
    }
    return aggregates;
  }

  /** 本番の集約の宣言を、テーブルと列の名前で返す。 */
  public static List<DeclaredAggregate> declared() {
    return all().stream()
        .map(aggregate ->
            new DeclaredAggregate(
                qualified(aggregate.table()),
                aggregate.children().stream()
                    .map(child ->
                        new DeclaredChild(qualified(child.table()), child.parentKey().getName()))
                    .toList()))
        .toList();
  }

  private static String qualified(final Table<?> table) {
    return table.getSchema().getName() + "." + table.getName();
  }
}
