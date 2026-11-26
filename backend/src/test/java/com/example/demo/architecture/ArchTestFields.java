package com.example.demo.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** このパッケージで {@link ArchTest} を付けた {@link ArchRule} のフィールドを、規則の一覧を確かめるテストで共有する。 */
final class ArchTestFields {

  private ArchTestFields() {}

  /** このパッケージのクラスを import して返す。 */
  /* package */ static Stream<JavaClass> architectureClasses() {
    return new ClassFileImporter().importPackages(ArchTestFields.class.getPackageName()).stream();
  }

  /** メンバーが {@link ArchTest} を付けた {@link ArchRule} のフィールドかを返す。 */
  /* package */ static boolean isArchRuleField(final JavaMember member) {
    return member instanceof JavaField field
        && field.isAnnotatedWith(ArchTest.class)
        && field.getRawType().isEquivalentTo(ArchRule.class);
  }

  /** このパッケージで {@link ArchTest} を付けた {@link ArchRule} のフィールドを、完全修飾名の順で返す。 */
  /* package */ static List<RuleField> ruleFields() {
    return architectureClasses()
        .flatMap(javaClass -> javaClass.getFields().stream())
        .filter(ArchTestFields::isArchRuleField)
        .sorted(Comparator.comparing(JavaField::getFullName))
        .map(field ->
            new RuleField(field.getOwner().getSimpleName(), field.getName(), ruleOf(field)))
        .toList();
  }

  private static ArchRule ruleOf(final JavaField field) {
    try {
      return (ArchRule) field.reflect().get(null);
    } catch (IllegalAccessException exception) {
      throw new IllegalStateException("規則のフィールドを読めない: " + field.getFullName(), exception);
    }
  }

  /**
   * {@link ArchTest} を付けた規則のフィールド。
   *
   * @param owner フィールドを持つクラスの単純名
   * @param name フィールドの名前。規則の名前として使う
   * @param rule フィールドの値の規則
   */
  /* package */ record RuleField(String owner, String name, ArchRule rule) {}
}
