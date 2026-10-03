package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ArchUnit の各規則の because が、理由、直し方、規約の文書のパスを持つことを確かめる。
 *
 * <p>規則は、このパッケージで {@link ArchTest} を付けた {@link ArchRule} のフィールドから集める。 because は「{@code
 * <理由>。直し方：<修正>。規約：<docs のパス>}」の形にし、規約の文書のパスは「、」で区切って複数書いてよい。
 */
class ArchitectureRuleMessageTest {

  /**
   * 理由を docs にも ADR にも書いていない規則（{@code <クラス名>.<フィールド名>}）。
   *
   * <p>これらの because は理由を書かず「直し方：」から始める。理由を docs か ADR に書いたら、ここから外して because に理由を足す。
   */
  private static final Set<String> PENDING_RATIONALE =
      Set.of(
          "PackageByFeatureOnionArchitectureTest.infrastructureDependsOnlyOnDomainModel",
          "PackageByFeatureOnionArchitectureTest.presentationDoesNotDependOnDomain",
          "ClassRoleArchTest.commandHandlersDoNotDependOnOtherCommandHandlers",
          "GeneralCodingRulesArchTest.noClassesShouldThrowGenericExceptions",
          "GeneralCodingRulesArchTest.noClassesShouldUseJavaUtilLogging",
          "GeneralCodingRulesArchTest.featureCodeUsesOnlySlf4jFacade",
          "GeneralCodingRulesArchTest.noClassesShouldUseFieldInjection",
          "GeneralCodingRulesArchTest.autowiredMethodsAreNotUsed",
          "GeneralCodingRulesArchTest.assertionsShouldHaveDetailMessage",
          "GeneralCodingRulesArchTest.deprecatedApiShouldNotBeUsed",
          "ProxyRulesArchTest.transactionalMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.asyncMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.cacheableMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.cachePutMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.cacheEvictMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.preAuthorizeMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.postAuthorizeMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.preFilterMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.postFilterMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.securedMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.circuitBreakerMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.retryMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.rateLimiterMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.bulkheadMethodsAreNotCalledFromSameClass",
          "ProxyRulesArchTest.timeLimiterMethodsAreNotCalledFromSameClass",
          "TestConventionsArchTest.testMethodsAreNotPublic",
          "TestConventionsArchTest.testClassesAreNotPublic",
          "TestConventionsArchTest.testClassesEndWithTest",
          "TestConventionsArchTest.assertTimeoutPreemptivelyIsNotUsed",
          "TestConventionsArchTest.disabledTestMethodsRequireReason",
          "TestConventionsArchTest.disabledTestClassesRequireReason");

  /** ArchUnit が規則の説明と because の文をつなぐ区切り。最後の区切りの後が、このプロジェクトで書いた because である。 */
  private static final String BECAUSE = ", because ";

  /** because の形。理由、直し方、規約の文書のパスの順に取り出す。 */
  private static final Pattern FORMAT = Pattern.compile("(?s)(.*?)直し方：(.+)。規約：(docs/.+)");

  /** 規約の文書のパスを解決するリポジトリのルート。Gradle はテストを backend で実行する。 */
  private static final Path REPOSITORY_ROOT = Path.of("..");

  /** 失敗したときに読む文書。 */
  private static final String GUIDE = "（docs/backend/architecture-tests.md）";

  @ParameterizedTest(name = "{0}")
  @MethodSource("declaredRules")
  @DisplayName("各規則の because は理由、直し方、規約の文書のパスを持つ")
  void eachRuleExplainsWhyAndHowToFix(final String ruleName, final ArchRule rule) {
    final String reason = becauseOf(ruleName, rule).group(1);

    if (PENDING_RATIONALE.contains(ruleName)) {
      assertThat(reason)
          .as(
              "%s は PENDING_RATIONALE にあるが、because に理由がある。"
                  + "理由を docs か ADR に書いたなら PENDING_RATIONALE から外し、書いていないなら理由を消す%s",
              ruleName, GUIDE)
          .isEmpty();
    } else {
      assertThat(reason)
          .as(
              "%s の because に理由がない。失敗した作業者が規則の目的を知れるようにするため。"
                  + "docs か ADR にある理由を「直し方：」の前に「…ため。」と書く。"
                  + "どこにも理由がなければ、規則を PENDING_RATIONALE に加える%s",
              ruleName, GUIDE)
          .isNotBlank()
          .endsWith("。");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("declaredRules")
  @DisplayName("各規則の because が示す規約の文書はリポジトリにある")
  void eachRuleReferencesExistingDocs(final String ruleName, final ArchRule rule) {
    for (final String doc : becauseOf(ruleName, rule).group(3).split("、", -1)) {
      assertThat(REPOSITORY_ROOT.resolve(doc))
          .as(
              "%s の「規約：」の %s がない。失敗した作業者が読む文書をたどれるようにするため。" + "リポジトリのルートからの docs/ のパスに直す%s",
              ruleName, doc, GUIDE)
          .isRegularFile();
    }
  }

  @Test
  @DisplayName("理由の確認待ちの一覧は宣言された規則だけを指す")
  void pendingRationaleNamesOnlyDeclaredRules() {
    final List<Object> declared = declaredRules().map(arguments -> arguments.get()[0]).toList();

    assertThat(declared)
        .as("PENDING_RATIONALE に、宣言されていない規則がある。規則の名前の変更に合わせて直すか、一覧から外す%s", GUIDE)
        .containsAll(PENDING_RATIONALE);
  }

  @Test
  @DisplayName("@ArchTest は ArchRule のフィールドにだけ付ける")
  void archTestsAreArchRuleFields() {
    final List<String> others =
        architectureClasses()
            .flatMap(javaClass -> javaClass.getMembers().stream())
            .filter(member -> member.isAnnotatedWith(ArchTest.class))
            .filter(member -> !isArchRuleField(member))
            .map(JavaMember::getFullName)
            .toList();

    assertThat(others)
        .as(
            "@ArchTest のメソッドと ArchTests のフィールドは because を検査できない。"
                + "規則を ArchRule の static final フィールドに書き直す%s",
            GUIDE)
        .isEmpty();
  }

  /** このパッケージで {@link ArchTest} を付けた {@link ArchRule} のフィールドを、名前と規則の組で返す。 */
  private static Stream<Arguments> declaredRules() {
    return architectureClasses()
        .flatMap(javaClass -> javaClass.getFields().stream())
        .filter(ArchitectureRuleMessageTest::isArchRuleField)
        .sorted(Comparator.comparing(JavaField::getFullName))
        .map(
            field ->
                Arguments.of(
                    field.getOwner().getSimpleName() + "." + field.getName(), ruleOf(field)));
  }

  private static Stream<JavaClass> architectureClasses() {
    return new ClassFileImporter()
        .importPackages(ArchitectureRuleMessageTest.class.getPackageName()).stream();
  }

  private static boolean isArchRuleField(final JavaMember member) {
    return member instanceof JavaField field
        && field.isAnnotatedWith(ArchTest.class)
        && field.getRawType().isEquivalentTo(ArchRule.class);
  }

  private static ArchRule ruleOf(final JavaField field) {
    try {
      return (ArchRule) field.reflect().get(null);
    } catch (IllegalAccessException exception) {
      throw new IllegalStateException("規則のフィールドを読めない: " + field.getFullName(), exception);
    }
  }

  /** 規則の最後の because を取り出し、形に合うことを確かめてから、理由、直し方、規約の組を返す。 */
  private static Matcher becauseOf(final String ruleName, final ArchRule rule) {
    final String description = rule.getDescription();
    assertThat(description)
        .as("%s に because がない。.because(\"<理由>。直し方：<修正>。規約：<docs のパス>\") を付ける%s", ruleName, GUIDE)
        .contains(BECAUSE);

    final String because =
        description.substring(description.lastIndexOf(BECAUSE) + BECAUSE.length());
    assertThat(because)
        .as(
            "%s の because が「<理由>。直し方：<修正>。規約：<docs のパス>」の形でない。"
                + "失敗した作業者が理由、直し方、読む文書を知れるようにするため。この形に直す%s",
            ruleName, GUIDE)
        .matches(FORMAT);

    final Matcher matcher = FORMAT.matcher(because);
    assertThat(matcher.matches()).isTrue();
    return matcher;
  }
}
