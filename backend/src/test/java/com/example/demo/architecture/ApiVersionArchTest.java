package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/} の Controller が、パスに {@code v{version}} を持ち、{@code version} を宣言することを検査する（ADR-138）。
 *
 * <p>{@code version} を書かない mapping は、どのバージョンのリクエストにも一致する。パスが {@code /api/v{version}/...}
 * でなければ、バージョンを読む区間がずれて、リクエストがすべて 400 になる。
 *
 * <p>規則は {@code xxxRule(basePackage)} のファクトリで組み立て、{@link ArchitectureRuleFixtureTest}
 * がフィクスチャのパッケージで同じ規則を再利用する。
 */
// ArchUnit の JUnit 5 エンジンが @ArchTest のフィールドを読むため、規則ファクトリだけを持つクラスでもユーティリティクラスにしない。
@SuppressWarnings({"PMD.TestClassWithoutTestCases", "PMD.UseUtilityClass"})
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class ApiVersionArchTest {

  /** プロダクションコードの基底パッケージ。 */
  private static final String BASE_PACKAGE = DemoApplication.class.getPackageName();

  /** API のパスの接頭辞。 */
  private static final String API_PREFIX = "/api/";

  /** バージョンを置くパスの接頭辞。{@code WebApiVersionConfig} がこの位置の区間を読む。 */
  private static final String VERSIONED_PREFIX = "/api/v{version}/";

  /** 規則と規約の文書。 */
  private static final String DOCS =
      "docs/web-api/versioning.md、docs/adr/ADR-138-introduce-api-v1-with-spring-api-versioning.md";

  /** {@code /api/} の Controller は、パスを {@code /api/v{version}/} で始め、{@code version} を宣言する。 */
  @ArchTest
  /* package */ static final ArchRule apiControllersDeclareVersion =
      apiControllersDeclareVersionRule(BASE_PACKAGE);

  /**
   * {@code /api/} の Controller の規則を組み立てる。
   *
   * <p>ponytail: クラスに付けた {@code @RequestMapping} だけを見る。メソッドに {@code /api/} のパスを書く Controller
   * が現れたら、その mapping も判定に足す。
   */
  /* package */ static ArchRule apiControllersDeclareVersionRule(final String basePackage) {
    return classes()
        .that()
        .areAnnotatedWith(RestController.class)
        .and()
        .resideInAPackage(basePackage + ".*.presentation.web..")
        .and(
            DescribedPredicate.describe(
                "@RequestMapping のパスが /api/ で始まる",
                type -> mappedPaths(type).stream().anyMatch(path -> path.startsWith(API_PREFIX))))
        .should(declareVersionInPathAndAttribute())
        .allowEmptyShould(true)
        .because(
            "version を書かない mapping はどのバージョンにも一致し、パスに v{version} がないと未対応のバージョンを 400 で拒否できないため。"
                + "直し方：@RequestMapping(path = \"/api/v{version}/<リソース>\", version = \"1\") と書く。"
                + "規約："
                + DOCS);
  }

  private static List<String> mappedPaths(final JavaClass type) {
    return type.tryGetAnnotationOfType(RequestMapping.class)
        .map(mapping ->
            Arrays.stream(mapping.path().length > 0 ? mapping.path() : mapping.value()).toList())
        .orElse(List.of());
  }

  private static ArchCondition<JavaClass> declareVersionInPathAndAttribute() {
    return new ArchCondition<>("start the path with /api/v{version}/ and declare version") {
      @Override
      public void check(final JavaClass type, final ConditionEvents events) {
        final boolean pathsVersioned =
            mappedPaths(type).stream().allMatch(path -> path.startsWith(VERSIONED_PREFIX));
        final boolean versionDeclared =
            type.tryGetAnnotationOfType(RequestMapping.class)
                .map(mapping -> !mapping.version().isBlank())
                .orElse(false);
        if (!pathsVersioned || !versionDeclared) {
          events.add(
              SimpleConditionEvent.violated(
                  type,
                  type.getName()
                      + " の @RequestMapping が、パスを /api/v{version}/ で始め、version を宣言していない。"));
        }
      }
    };
  }
}
