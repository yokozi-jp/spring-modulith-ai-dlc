package com.example.demo.shared.persistence;

import com.example.demo.DemoApplication;
import com.example.demo.architecture.ProductionCodeOnly;
import com.example.demo.shared.persistence.DeclaredAggregate.DeclaredChild;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jooq.Table;
import org.jooq.TableField;

/** 本番のクラスが宣言した集約を、テストから読む。 */
public final class AggregateDeclarations {

  private AggregateDeclarations() {
    // 静的メソッドだけを持つ。
  }

  /** 本番のクラスの、AggregateTable 型の static フィールドの値を集める。 */
  /* package */ static List<AggregateTable<?, ?, ?>> all() {
    return byOwner().values().stream().flatMap(List::stream).toList();
  }

  /** 本番のクラスの、AggregateTable 型の static フィールドの値を、宣言したクラスごとに集める。 */
  /* package */ static Map<Class<?>, List<AggregateTable<?, ?, ?>>> byOwner() {
    return new ClassFileImporter()
        .withImportOption(new ProductionCodeOnly()).importPackagesOf(DemoApplication.class).stream()
            .map(JavaClass::reflect)
            .map(owner -> Map.entry(owner, declaredIn(owner)))
            .filter(entry -> !entry.getValue().isEmpty())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static List<AggregateTable<?, ?, ?>> declaredIn(final Class<?> owner) {
    return Arrays.stream(owner.getDeclaredFields())
        .filter(field ->
            Modifier.isStatic(field.getModifiers())
                && AggregateTable.class.isAssignableFrom(field.getType()))
        .<AggregateTable<?, ?, ?>>map(AggregateDeclarations::read)
        .toList();
  }

  // private な定数の宣言を読むため、可視性を変える。
  @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
  private static AggregateTable<?, ?, ?> read(final Field field) {
    field.setAccessible(true);
    try {
      return (AggregateTable<?, ?, ?>) field.get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("cannot read " + field, e);
    }
  }

  /** 本番の集約の宣言を、テーブルと列の名前で返す。 */
  public static List<DeclaredAggregate> declared() {
    return all().stream()
        .map(aggregate ->
            new DeclaredAggregate(
                qualified(aggregate.table()),
                aggregate.key().getName(),
                aggregate.children().stream()
                    .map(child ->
                        new DeclaredChild(qualified(child.table()), child.parentKey().getName()))
                    .toList(),
                aggregate.duplicateKey().stream().map(TableField::getName).toList()))
        .toList();
  }

  private static String qualified(final Table<?> table) {
    return Objects.requireNonNull(table.getSchema()).getName() + "." + table.getName();
  }
}
