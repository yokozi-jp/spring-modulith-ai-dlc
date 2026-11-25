package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.example.demo.DemoApplication;
import com.example.demo.shared.security.NoRoleRequired;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.base.HasDescription;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;

/** 役割の確かめ（Spring Security の method security）の置き場所、式、宣言の漏れと重なりを確かめる（ADR-129）。 */
// ArchUnit の JUnit 5 エンジンが @ArchTest フィールドを読むため、規則と規則のファクトリだけを持つクラスでもユーティリティクラスにしない。
@SuppressWarnings({"PMD.TestClassWithoutTestCases", "PMD.UseUtilityClass"})
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class AuthorizationArchTest {

  /** because の最後に置く、層の責務の規約への参照。 */
  private static final String LAYERS_DOC = "規約：docs/backend/layers.md";

  /** {@link ProxyRulesArchTest#METHOD_SECURITY_ANNOTATIONS} のどれかを、直接かメタアノテーションとして持つクラスとメソッド。 */
  private static final DescribedPredicate<CanBeAnnotated> METHOD_SECURED =
      ProxyRulesArchTest.metaAnnotatedWithOneOf(ProxyRulesArchTest.METHOD_SECURITY_ANNOTATIONS);

  /** {@code @PreAuthorize} を、直接かメタアノテーションとして持つクラスとメソッド。 */
  private static final DescribedPredicate<CanBeAnnotated> PRE_AUTHORIZED =
      CanBeAnnotated.Predicates.metaAnnotatedWith(PreAuthorize.class);

  /** {@code @PreAuthorize} に書いてよい式。引用符で囲んだ役割か権限の名前を、1 つの関数に並べるだけにする。 */
  private static final Pattern ROLE_OR_AUTHORITY_NAMES =
      Pattern.compile(
          "(hasRole|hasAuthority)\\('[\\w:.-]+'\\)"
              + "|(hasAnyRole|hasAnyAuthority)\\('[\\w:.-]+'(\\s*,\\s*'[\\w:.-]+')*\\)");

  /**
   * method security のアノテーションは、各モジュールの application のクラスとそのメソッドにだけ付ける。
   *
   * <p>Controller、domain、infrastructure に付けると、同じ操作の役割の確かめ方が層ごとに散る。
   */
  @ArchTest
  /* package */ static final ArchRule methodSecurityAnnotationsAreOnlyOnApplicationClasses =
      methodSecurityAnnotationsAreOnlyOnApplicationClassesRule(
          DemoApplication.class.getPackageName());

  /** {@code @PreAuthorize} には、役割か権限の名前だけを書く。 */
  @ArchTest
  /* package */ static final ArchRule preAuthorizeUsesOnlyRoleOrAuthorityNames =
      classesAndMethods(
              CanBeAnnotated.Predicates.annotatedWith(PreAuthorize.class),
              useOnlyRoleOrAuthorityNames(),
              useOnlyRoleOrAuthorityNames())
          .because(
              "誰に許すかを役割で決め、見える範囲は Repository の SQL で絞ると決めており（ADR-129）、"
                  + "引数や Bean を参照する SpEL や、and と or で組んだ式を書くと、許す条件が式の中に隠れて二重になるため。"
                  + "直し方：@PreAuthorize の値を hasRole('ADMIN')、hasAnyRole('ADMIN', 'MEMBER')、"
                  + "hasAuthority('<権限>')、hasAnyAuthority('<権限>', ...) のどれか 1 つにし、"
                  + "本人や持ち主の確かめは、利用者の ID を Repository の WHERE に入れて行う。"
                  + LAYERS_DOC);

  /**
   * application の CommandHandler と QueryService の public
   * メソッドは、役割を確かめるか（{@code @PreAuthorize}）、確かめないか（{@link NoRoleRequired}）を宣言する。
   *
   * <p>{@code @PreAuthorize} はメソッドかクラスに、直接かメタアノテーションとして付ける。static のメソッドはプロキシが横取りしないので除く。
   */
  @ArchTest
  /* package */ static final ArchRule applicationHandlersDeclareAuthorization =
      methods()
          .that()
          .arePublic()
          .and()
          .areNotStatic()
          .and()
          .areDeclaredInClassesThat(
              ClassRoleArchTest.APPLICATION_COMMAND_HANDLER.or(
                  ClassRoleArchTest.APPLICATION_QUERY_SERVICE))
          .should(declareAuthorization())
          .allowEmptyShould(true)
          .because(
              "認可の表のテストは operation を足したときだけ期待の書き忘れを止め、"
                  + "operation を通らずに呼ぶメソッド（Listener から呼ぶ CommandHandler）と、既存の operation の後ろに足したメソッドでは、"
                  + "役割を判断しないまま @PreAuthorize のないメソッドが残るため（ADR-129）。"
                  + "直し方：役割で許すメソッドには @PreAuthorize(\"hasRole('<役割>')\") を、"
                  + "役割を問わないメソッドには @NoRoleRequired(NoRoleRequired.Basis.<根拠>) を付ける。"
                  + "根拠は、見える範囲の絞り方や呼び出し元を表す NoRoleRequired.Basis から選ぶ。"
                  + LAYERS_DOC);

  /**
   * 1 つのメソッドの役割の判断は、{@code @PreAuthorize} か {@link NoRoleRequired} の一方だけで宣言する。
   *
   * <p>Spring はクラスの {@code @PreAuthorize} を、{@code @PreAuthorize} のないメソッドにも当てる。そのため
   * {@code @PreAuthorize} を付けたクラスのメソッドに {@link NoRoleRequired} を付けても、役割を確かめる。
   */
  @ArchTest
  /* package */ static final ArchRule authorizationIsDeclaredOnlyOnce =
      methods()
          .that()
          .areAnnotatedWith(NoRoleRequired.class)
          .should()
          .notBeMetaAnnotatedWith(PreAuthorize.class)
          .andShould()
          .beDeclaredInClassesThat(DescribedPredicate.not(PRE_AUTHORIZED))
          .allowEmptyShould(true)
          .because(
              "同じメソッドに両方を付けると、どちらの判断が正しいかがコードから読めず、"
                  + "@PreAuthorize を付けたクラスのメソッドでは、Spring がクラスの @PreAuthorize を当てて、"
                  + "@NoRoleRequired の宣言と違って役割を確かめるため。"
                  + "直し方：役割で許すなら @NoRoleRequired を外し、役割を問わないなら @PreAuthorize を外す。"
                  + "クラスに @PreAuthorize があるなら、クラスから外して、メソッドごとに @PreAuthorize か @NoRoleRequired を付ける。"
                  + LAYERS_DOC);

  /** method security のアノテーションを各モジュールの application にだけ置く規則を組み立てる。 */
  /* package */ static ArchRule methodSecurityAnnotationsAreOnlyOnApplicationClassesRule(
      final String basePackage) {
    final String application =
        basePackage + PackageByFeatureOnionArchitectureTest.MODULE_APPLICATION;
    return classesAndMethods(
            METHOD_SECURED,
            ArchConditions.resideInAPackage(application),
            ArchConditions.beDeclaredInClassesThat(
                JavaClass.Predicates.resideInAPackage(application)))
        .because(
            "誰が要求したか（役割を含む）は application で確かめると決めており（ADR-129）、"
                + "Controller、domain、infrastructure に付けると、操作の役割の確かめ方が層ごとに散るため。"
                + "直し方："
                + ProxyRulesArchTest.annotationNames(
                    ProxyRulesArchTest.METHOD_SECURITY_ANNOTATIONS, "、")
                + " と、それをメタアノテーションに持つ注釈を、Controller、domain、infrastructure のクラスとメソッドから外し、"
                + "操作を受け持つ各モジュールの application の CommandHandler の handle に、"
                + "役割か権限の名前だけを書いた @PreAuthorize を付ける。"
                + LAYERS_DOC);
  }

  /** 述語に当てはまるクラスとメソッドに、それぞれの条件を当てる規則を組み立てる。 */
  private static CompositeArchRule classesAndMethods(
      final DescribedPredicate<CanBeAnnotated> predicate,
      final ArchCondition<? super JavaClass> classCondition,
      final ArchCondition<? super JavaMethod> methodCondition) {
    return CompositeArchRule.of(
            classes().that(predicate).should(classCondition).allowEmptyShould(true))
        .and(methods().that(predicate).should(methodCondition).allowEmptyShould(true));
  }

  private static <T extends HasAnnotations<?> & HasDescription>
      ArchCondition<T> useOnlyRoleOrAuthorityNames() {
    return new ArchCondition<>("write only role or authority names in @PreAuthorize") {
      @Override
      public void check(final T item, final ConditionEvents events) {
        item.tryGetAnnotationOfType(PreAuthorize.class)
            .map(PreAuthorize::value)
            .filter(expression -> !ROLE_OR_AUTHORITY_NAMES.matcher(expression).matches())
            .ifPresent(expression ->
                events.add(
                    SimpleConditionEvent.violated(
                        item,
                        item.getDescription()
                            + " の @PreAuthorize(\""
                            + expression
                            + "\") は、役割か権限の名前だけの式でない。")));
      }
    };
  }

  private static ArchCondition<JavaMethod> declareAuthorization() {
    return new ArchCondition<>("be annotated with @PreAuthorize or @NoRoleRequired") {
      @Override
      public void check(final JavaMethod item, final ConditionEvents events) {
        if (!PRE_AUTHORIZED.test(item)
            && !item.isAnnotatedWith(NoRoleRequired.class)
            && !PRE_AUTHORIZED.test(item.getOwner())) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getFullName()
                      + " に @PreAuthorize も @NoRoleRequired もなく、クラスにも @PreAuthorize がない。 "
                      + item.getSourceCodeLocation()));
        }
      }
    };
  }
}
