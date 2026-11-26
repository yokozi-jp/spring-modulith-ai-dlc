package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * {@code /api/} の Controller が、パスに {@code v{version}} を持ち、メジャーバージョンの {@code version}
 * を宣言することを検査する（ADR-138）。
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

  /** API のパスの根。 */
  private static final String API_ROOT = "/api";

  /** API のパスの接頭辞。 */
  private static final String API_PREFIX = "/api/";

  /** バージョンを置くパスの接頭辞。{@code WebApiVersionConfig} がこの位置の区間を読む。 */
  private static final String VERSIONED_PREFIX = "/api/v{version}/";

  /** 規則と規約の文書。 */
  private static final String DOCS =
      "docs/web-api/versioning.md、docs/adr/ADR-138-introduce-api-v1-with-spring-api-versioning.md";

  /** {@code version} に書ける値。メジャーバージョンの正の整数だけで、{@code 1.1} や {@code 1+} は書かない。 */
  private static final Pattern MAJOR_VERSION = Pattern.compile("^[1-9][0-9]*$");

  /**
   * {@code /api/} の Controller は、クラスのパスを {@code /api/v{version}/} で始め、メジャーバージョンの {@code version}
   * を宣言する。
   */
  @ArchTest
  /* package */ static final ArchRule apiControllersDeclareVersion =
      apiControllersDeclareVersionRule(BASE_PACKAGE);

  /**
   * {@code /api/} の Controller の規則を組み立てる。
   *
   * <p>{@code /api/} の Controller は、クラスかメソッドの mapping のパスが {@code /api/} で始まるものである。メソッドの mapping に
   * {@code /api/} の絶対のパスを書くと、クラスの {@code version} を迂回するので、メソッドのパスは相対にする。
   */
  /* package */ static ArchRule apiControllersDeclareVersionRule(final String basePackage) {
    return classes()
        .that()
        .areMetaAnnotatedWith(Controller.class)
        .and()
        .resideInAPackage(basePackage + "..")
        .and(
            DescribedPredicate.describe(
                "クラスかメソッドの mapping のパスが /api/ で始まる",
                type ->
                    classPaths(type).anyMatch(ApiVersionArchTest::isApiPath)
                        || methodPaths(type).anyMatch(ApiVersionArchTest::isApiPath)))
        .should(declareVersionInPathAndAttribute())
        .allowEmptyShould(true)
        .because(
            "version を書かない mapping はどのバージョンにも一致し、パスに v{version} がないと未対応のバージョンを 400 で拒否できないため。"
                + "メソッドに /api/ のパスを書くと、クラスの version を迂回するため。"
                + "直し方：クラスに @RequestMapping(path = \"/api/v{version}/<リソース>\", version = \"1\") を書き、"
                + "メソッドのパスは相対にする。"
                + "規約："
                + DOCS);
  }

  private static boolean isApiPath(final String path) {
    return API_ROOT.equals(path) || path.startsWith(API_PREFIX);
  }

  private static Stream<String> classPaths(final JavaClass type) {
    return type.tryGetAnnotationOfType(RequestMapping.class).stream()
        .flatMap(mapping -> paths(mapping.path(), mapping.value()));
  }

  private static Stream<String> methodPaths(final JavaClass type) {
    return type.getMethods().stream()
        .flatMap(method -> {
          final Stream<String> request =
              method.tryGetAnnotationOfType(RequestMapping.class).stream()
                  .flatMap(mapping -> paths(mapping.path(), mapping.value()));
          return Stream.of(
                  request,
                  mappedPaths(method, GetMapping.class, GetMapping::path, GetMapping::value),
                  mappedPaths(method, PostMapping.class, PostMapping::path, PostMapping::value),
                  mappedPaths(method, PutMapping.class, PutMapping::path, PutMapping::value),
                  mappedPaths(method, PatchMapping.class, PatchMapping::path, PatchMapping::value),
                  mappedPaths(
                      method, DeleteMapping.class, DeleteMapping::path, DeleteMapping::value))
              .flatMap(Function.identity());
        });
  }

  private static <A extends Annotation> Stream<String> mappedPaths(
      final JavaMethod method,
      final Class<A> annotation,
      final Function<A, String[]> path,
      final Function<A, String[]> value) {
    return method.tryGetAnnotationOfType(annotation).stream()
        .flatMap(mapping -> paths(path.apply(mapping), value.apply(mapping)));
  }

  // 注釈の path と value の 2 つの配列を受けるので、最後の引数を可変長にできない。
  /** {@code path} と {@code value} は別名で、ArchUnit は別名を解決しないので、書かれているほうを読む。 */
  @SuppressWarnings("PMD.UseVarargs")
  private static Stream<String> paths(final String[] path, final String[] value) {
    return Arrays.stream(path.length > 0 ? path : value);
  }

  private static ArchCondition<JavaClass> declareVersionInPathAndAttribute() {
    return new ArchCondition<>("start the path with /api/v{version}/ and declare a major version") {
      @Override
      public void check(final JavaClass type, final ConditionEvents events) {
        final List<String> classPaths = classPaths(type).toList();
        if (classPaths.isEmpty()
            || !classPaths.stream().allMatch(p -> p.startsWith(VERSIONED_PREFIX))) {
          events.add(
              SimpleConditionEvent.violated(
                  type, type.getName() + " の @RequestMapping のパスが /api/v{version}/ で始まっていない。"));
        }
        final String version =
            type.tryGetAnnotationOfType(RequestMapping.class)
                .map(RequestMapping::version)
                .orElse("");
        if (!MAJOR_VERSION.matcher(version).matches()) {
          events.add(
              SimpleConditionEvent.violated(
                  type,
                  type.getName()
                      + " の @RequestMapping の version が、正の整数のメジャーバージョンでない（値：\""
                      + version
                      + "\"）。"));
        }
        methodPaths(type)
            .filter(ApiVersionArchTest::isApiPath)
            .forEach(path ->
                events.add(
                    SimpleConditionEvent.violated(
                        type,
                        type.getName()
                            + " のメソッドの mapping に /api/ の絶対のパス "
                            + path
                            + " がある。クラスの version を迂回する。")));
      }
    };
  }
}
