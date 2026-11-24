package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 主キーをモジュールの外へ出さないため、モジュールの外に出る型に数値の ID を置かせない（docs/database/postgresql-primary-keys.md）。
 *
 * <p>規則は {@code numericIdsDoNotLeaveModulesRule(basePackage)} のファクトリで組み立て、{@link
 * ArchitectureRuleFixtureTest} がフィクスチャのパッケージで同じ規則を再利用する。
 */
// ArchUnit の JUnit 5 エンジンが @ArchTest フィールドを読むため、規則ファクトリだけを持つクラスでもユーティリティクラスにしない。
@SuppressWarnings({"PMD.TestClassWithoutTestCases", "PMD.UseUtilityClass"})
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class PublicIdArchTest {

  /** プロダクションコードの基底パッケージ。 */
  private static final String BASE_PACKAGE = DemoApplication.class.getPackageName();

  /** 主キーとみなす数値の型。失敗メッセージに並べる順を固定するため List にする。 */
  private static final List<String> NUMERIC_TYPES =
      List.of("long", "java.lang.Long", "int", "java.lang.Integer");

  /** 数値の ID とみなす名前。 */
  private static final List<String> ID_NAMES = List.of("id", "ids");

  /** 数値の ID とみなす名前の末尾。 */
  private static final List<String> ID_SUFFIXES = List.of("Id", "Ids");

  /** モジュールの外に出る型は、数値の {@code *Id} を持たない。 */
  @ArchTest
  /* package */ static final ArchRule numericIdsDoNotLeaveModules =
      numericIdsDoNotLeaveModulesRule(BASE_PACKAGE);

  /** モジュールの外に出る型が数値の {@code *Id} を持たない規則を組み立てる。 */
  /* package */ static ArchRule numericIdsDoNotLeaveModulesRule(final String basePackage) {
    return classes()
        .that()
        .resideInAnyPackage(
            basePackage + ".*",
            basePackage + ".*.domain..",
            basePackage + ".*.application..",
            basePackage + ".*.infrastructure.client..",
            basePackage + ".shared..")
        .and()
        .resideOutsideOfPackage(basePackage + ".shared.persistence..")
        .should(notHoldNumericIds())
        .allowEmptyShould(true)
        .because(
            "主キーは同じモジュールのスキーマと Repository の中だけで使い、モジュールの外に出る値には public_id を使うため。"
                + "直し方：違反した項目を、主キーの数値ではなく public_id の値にする。"
                + "対象の型は「使い分けの検査」に、値の形は「主キーと公開用IDの使い分け」に示す。"
                + "規約：docs/database/postgresql-primary-keys.md");
  }

  private static ArchCondition<JavaClass> notHoldNumericIds() {
    return new ArchCondition<>(
        "not have fields, parameters, or methods named "
            + Stream.concat(ID_NAMES.stream(), ID_SUFFIXES.stream().map(suffix -> "*" + suffix))
                .toList()
            + " whose type is or contains "
            + NUMERIC_TYPES) {
      @Override
      public void check(final JavaClass javaClass, final ConditionEvents events) {
        for (final JavaField field : javaClass.getFields()) {
          if (isNumericId(field.getName(), field.reflect().getGenericType())) {
            events.add(SimpleConditionEvent.violated(field, field.getFullName() + " は数値の ID である"));
          }
        }
        for (final JavaMethod method : javaClass.getMethods()) {
          if (isNumericId(method.getName(), method.reflect().getGenericReturnType())) {
            events.add(
                SimpleConditionEvent.violated(method, method.getFullName() + " は数値の ID を返す"));
          }
        }
        Stream.concat(javaClass.getMethods().stream(), javaClass.getConstructors().stream())
            .forEach(codeUnit -> checkParameters(codeUnit, events));
      }
    };
  }

  /** ArchUnit は引数の名前を読まないため、コンパイラの {@code -parameters} が残した名前をリフレクションで読む。 */
  private static void checkParameters(final JavaCodeUnit codeUnit, final ConditionEvents events) {
    for (final Parameter parameter : ((Executable) codeUnit.reflect()).getParameters()) {
      if (isNumericId(parameter.getName(), parameter.getParameterizedType())) {
        events.add(
            SimpleConditionEvent.violated(
                codeUnit, codeUnit.getFullName() + " の引数 " + parameter.getName() + " は数値の ID である"));
      }
    }
  }

  private static boolean isNumericId(final String name, final Type type) {
    return (ID_NAMES.contains(name) || ID_SUFFIXES.stream().anyMatch(name::endsWith))
        && containsNumeric(type);
  }

  /** {@code List<Long>}、{@code Optional<Long>}、{@code long[]} のように、型引数と配列の要素に数値を含む型も数値の ID とみなす。 */
  private static boolean containsNumeric(final Type type) {
    if (type instanceof Class<?> c) {
      return c.isArray()
          ? containsNumeric(c.getComponentType())
          : NUMERIC_TYPES.contains(c.getName());
    }
    if (type instanceof ParameterizedType p) {
      return Arrays.stream(p.getActualTypeArguments()).anyMatch(PublicIdArchTest::containsNumeric);
    }
    if (type instanceof GenericArrayType g) {
      return containsNumeric(g.getGenericComponentType());
    }
    return type instanceof WildcardType w
        && Arrays.stream(w.getUpperBounds()).anyMatch(PublicIdArchTest::containsNumeric);
  }
}
