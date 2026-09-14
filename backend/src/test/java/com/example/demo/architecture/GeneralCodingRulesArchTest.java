package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.springframework.beans.factory.annotation.Autowired;

/** ArchUnit が提供する汎用コーディング規則をプロダクションコードへ適用する。 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class GeneralCodingRulesArchTest {

  /** 標準出力と標準エラー出力への直接アクセスを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule noClassesShouldAccessStandardStreams =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  /** {@link Exception} や {@link RuntimeException} のような汎用例外の送出を禁止する。 */
  @ArchTest
  /* package */ static final ArchRule noClassesShouldThrowGenericExceptions =
      GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

  /** {@code java.util.logging} の使用を禁止し、アプリケーションのロギング実装を統一する。 */
  @ArchTest
  /* package */ static final ArchRule noClassesShouldUseJavaUtilLogging =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

  /** 機能コードからロギング実装 API への直接依存を禁止し、SLF4J facade を使用する。 */
  @ArchTest
  /* package */ static final ArchRule featureCodeUsesOnlySlf4jFacade =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.demo")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "ch.qos.logback..",
              "org.apache.logging.log4j..",
              "org.apache.log4j..",
              "org.apache.commons.logging..")
          .allowEmptyShould(true)
          .because(
              "機能コードのロギングは SLF4J facade を使い、"
                  + "Logback、Log4j、Apache Commons Logging の実装 API へ直接依存させない。");

  /** Joda-Time の使用を禁止し、日時 API を {@code java.time} に統一する。 */
  @ArchTest
  /* package */ static final ArchRule noClassesShouldUseJodaTime =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;

  /** フィールドインジェクションを禁止し、依存をコンストラクタで明示する。 */
  @ArchTest
  /* package */ static final ArchRule noClassesShouldUseFieldInjection =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

  /** {@code @Autowired} を通常メソッドへ付けるメソッドインジェクションを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule autowiredMethodsAreNotUsed =
      noMethods()
          .should()
          .beAnnotatedWith(Autowired.class)
          .because("依存はコンストラクタ引数で明示し、単一コンストラクタでは @Autowired を省略する。");

  /** Java の {@code assert} 文に失敗理由のメッセージを必須とする。 */
  @ArchTest
  /* package */ static final ArchRule assertionsShouldHaveDetailMessage =
      GeneralCodingRules.ASSERTIONS_SHOULD_HAVE_DETAIL_MESSAGE;

  /** 非推奨 API の新規利用を禁止する。 */
  @ArchTest
  /* package */ static final ArchRule deprecatedApiShouldNotBeUsed =
      GeneralCodingRules.DEPRECATED_API_SHOULD_NOT_BE_USED;

  /** {@code java.util.Date} などの旧日時 API を禁止する。 */
  @ArchTest
  /* package */ static final ArchRule oldDateAndTimeClassesShouldNotBeUsed =
      GeneralCodingRules.OLD_DATE_AND_TIME_CLASSES_SHOULD_NOT_BE_USED;
}
