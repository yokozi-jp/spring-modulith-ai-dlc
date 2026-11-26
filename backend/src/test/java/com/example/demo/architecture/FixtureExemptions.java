package com.example.demo.architecture;

import static java.util.Map.entry;

import java.util.Map;

/**
 * {@link ArchitectureRuleFixtureTest} の違反の行と {@code rulesFor()} の両方には載せない {@code @ArchTest}
 * の規則と、載せない理由。
 */
final class FixtureExemptions {

  /** テストコードを検査する規則の理由。 */
  private static final String TEST_CODE =
      "TestCodeOnly でテストコードを検査する規則で、プロダクションコードの形を模した archfixture には対象のクラスがないため";

  /** {@code DirectWriteFixtureTest} が確かめる規則の理由。 */
  private static final String DIRECT_WRITE_FIXTURE_TEST =
      "DirectWriteFixtureTest が DirectOrderWriter のメソッドごとに、検出される禁止の呼び出しまで確かめるため";

  /** {@code SleepRuleFixtureTest} が確かめる規則の理由。 */
  private static final String SLEEP_RULE_FIXTURE_TEST =
      "テストコードを検査する規則で、SleepRuleFixtureTest が archfixture.sleep のクラスで確かめるため";

  /** {@code DatabaseConventionsArchTest} の中のテストが確かめる規則の理由。 */
  private static final String DATABASE_CONVENTION_BYPASS =
      "DatabaseConventionsArchTest の databaseConventionBypassesAreRejected が DatabaseConventionBypass で確かめるため";

  /** {@code DateTimeConventionsArchTest} の中のテストが確かめる規則の理由。 */
  private static final String INJECTED_CLOCK_TESTS =
      "DateTimeConventionsArchTest の @Test が、Clock を迂回するフィクスチャと許す書き方のフィクスチャで確かめるため";

  /** {@code JooqCommonColumnsArchTest} の中のテストが確かめる規則の理由。 */
  private static final String COMMON_COLUMN_BYPASS =
      "JooqCommonColumnsArchTest の commonColumnReferencesOutsideSharedAreRejected が確かめるため";

  /** {@code GeneralCodingRulesArchTest} の中のテストが確かめる規則の理由。 */
  private static final String REST_CLIENT_BYPASS =
      "GeneralCodingRulesArchTest の restClientFactoriesAreRejected が RestClientBypass で確かめるため";

  /** ArchUnit の {@code GeneralCodingRules} の定数に because を付けただけの規則の理由。 */
  private static final String LIBRARY_RULE =
      "ArchUnit の GeneralCodingRules の定数に because を付けただけの規則で、違反の検出は ArchUnit のテストが確かめるため";

  /** 規則の名前（{@code @ArchTest} のフィールドの名前）から、フィクスチャで確かめない理由への対応。 */
  /* package */ static final Map<String, String> REASONS =
      Map.ofEntries(
          entry("testsMustDeclareDisplayName", TEST_CODE),
          entry("testMethodsAreNotPublic", TEST_CODE),
          entry("testClassesAreNotPublic", TEST_CODE),
          entry("testClassesEndWithTest", TEST_CODE),
          entry("springBootTestsImportSharedConfiguration", TEST_CODE),
          entry("containersAreStartedOnlyByTestkit", TEST_CODE),
          entry("oidcLoginComesFromTestkit", TEST_CODE),
          entry("assertTimeoutPreemptivelyIsNotUsed", TEST_CODE),
          entry("legacyDateTimeTypesAreNotUsedInTests", TEST_CODE),
          entry("disabledTestMethodsRequireReason", TEST_CODE),
          entry("disabledTestClassesRequireReason", TEST_CODE),
          entry("sleepIsNotUsedInTests", SLEEP_RULE_FIXTURE_TEST),
          entry("tableWritesGoThroughTableWriter", DIRECT_WRITE_FIXTURE_TEST),
          entry("transactionIsolationIsNotDeclared", DATABASE_CONVENTION_BYPASS),
          entry("plainSqlApisAreNotUsed", DATABASE_CONVENTION_BYPASS),
          entry("verbatimSqlApisAreNotUsed", DATABASE_CONVENTION_BYPASS),
          entry("nameBuildingApisAreNotUsed", DATABASE_CONVENTION_BYPASS),
          entry("renderSchemaIsNotChanged", DATABASE_CONVENTION_BYPASS),
          entry("statementTypeAndParamTypeAreNotChanged", DATABASE_CONVENTION_BYPASS),
          entry("currentTimeIsObtainedThroughInjectedClock", INJECTED_CLOCK_TESTS),
          entry("commonColumnsAreReferencedOnlyBySharedPersistence", COMMON_COLUMN_BYPASS),
          entry("restClientIsBuiltFromInjectedBuilder", REST_CLIENT_BYPASS),
          entry("noClassesShouldAccessStandardStreams", LIBRARY_RULE),
          entry("noClassesShouldThrowGenericExceptions", LIBRARY_RULE),
          entry("noClassesShouldUseJavaUtilLogging", LIBRARY_RULE),
          entry("noClassesShouldUseJodaTime", LIBRARY_RULE),
          entry("noClassesShouldUseFieldInjection", LIBRARY_RULE),
          entry("assertionsShouldHaveDetailMessage", LIBRARY_RULE),
          entry("deprecatedApiShouldNotBeUsed", LIBRARY_RULE),
          entry("oldDateAndTimeClassesShouldNotBeUsed", LIBRARY_RULE));

  private FixtureExemptions() {}
}
