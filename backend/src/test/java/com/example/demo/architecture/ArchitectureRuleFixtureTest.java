package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Records;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * クラスの役割と配置の ArchUnit 規則が、空振りせずに違反を検出することを確かめる。
 *
 * <p>プロダクションコードにはまだ機能モジュールがなく、規則の多くは {@code allowEmptyShould(true)} で空のまま通る。 そこで {@code
 * com.example.demo} の外に置いたテスト専用のフィクスチャを import し、規約どおりの {@code archfixture.conforming}
 * がすべての規則を満たすこと、{@code archfixture.violating} の各クラスが対応する規則で検出されることを確かめる。
 */
// JavaClasses は ArchUnit の import 結果を表す公開 API 型であり、インタフェースへ置き換えられない。
@SuppressWarnings("PMD.LooseCoupling")
class ArchitectureRuleFixtureTest {

  /** 規約どおりのフィクスチャの基底パッケージ。 */
  private static final String CONFORMING = "archfixture.conforming";

  /** 違反フィクスチャの基底パッケージ。 */
  private static final String VIOLATING = "archfixture.violating";

  /** 違反フィクスチャのクラス名の接頭辞。 */
  private static final String VIOLATING_PREFIX = VIOLATING + ".";

  /** TableWriter を通さずに書く違反フィクスチャのクラス名。 */
  private static final String DIRECT_WRITER = "order.infrastructure.persistence.DirectOrderWriter";

  /** TableWriter の H1 で、違反フィクスチャのメソッドごとに確かめる禁止の API。 */
  private static final List<String> DIRECT_WRITES =
      List.of(
          "dslUpdate",
          "dslDelete",
          "dslDeleteFrom",
          "dslMergeInto",
          "dslBatchUpdate",
          "dslBatchStore",
          "dslBatchDelete",
          "dslBatchMerge",
          "dslExecuteUpdate",
          "dslExecuteDelete",
          "dslConnection",
          "dslConnectionResult",
          "dslUpdateQuery",
          "dslDeleteQuery",
          "withUpdate",
          "insertQueryOnDuplicateKeyUpdate",
          "insertQueryAddValueForUpdate",
          "loaderOnDuplicateKeyUpdate",
          "qomOnDuplicateKeyUpdate",
          "springScriptPopulator",
          "bootScriptInitializer",
          "springSqlUpdate",
          "staticDslUpdate",
          "lambdaExecute",
          "updateVariableExecute",
          "updateMethodReference",
          "updateReturning",
          "deleteExecute",
          "mergeExecute",
          "upsertOnConflict",
          "upsertOnConflictOnConstraint",
          "upsertOnDuplicateKeyUpdate",
          "recordStore",
          "recordUpdate",
          "recordDelete",
          "recordMerge",
          "daoUpdate",
          "daoDelete",
          "daoDeleteById",
          "daoMerge",
          "jdbcTemplate",
          "dataSourceConnection",
          "jdbcConnection",
          "jdbcStatement",
          "connectionProvider");

  /** 版を比べない入口を使う違反フィクスチャの Repository。 */
  private static final String UNVERSIONED_REPOSITORY =
      "order.infrastructure.persistence.JooqOrderRepository";

  /** リフレクションで対応づける違反フィクスチャのクラス名。 */
  private static final String REFLECTIVE_READER =
      "order.infrastructure.persistence.ReflectiveOrderReader";

  @Test
  @DisplayName("規約どおりのフィクスチャはすべてのクラス役割規則を満たす")
  void conformingFixtureSatisfiesEveryRule() {
    final JavaClasses conforming = new ClassFileImporter().importPackages(CONFORMING);

    for (final ArchRule rule : rulesFor(CONFORMING)) {
      assertThatCode(() -> rule.check(conforming)).doesNotThrowAnyException();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  @DisplayName("各クラス役割規則は対応する違反フィクスチャを検出する")
  void eachRuleDetectsItsViolatingFixture(
      final String ruleName, final ArchRule rule, final String violatingClass) {
    final JavaClasses violating = new ClassFileImporter().importPackages(VIOLATING);

    assertThatThrownBy(() -> rule.check(violating))
        .as(ruleName)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(violatingClass);
  }

  private static Stream<Arguments> eachRuleDetectsItsViolatingFixture() {
    return Stream.concat(classRoleRows(), tableWriterRows());
  }

  /** TableWriter の規則（H1、H3、H4、H5、H6）が、対応する違反フィクスチャを検出することを確かめる行を作る。 */
  private static Stream<Arguments> tableWriterRows() {
    return Stream.concat(
        DIRECT_WRITES.stream()
            .map(
                method ->
                    row(
                        "tableWritesGoThroughTableWriter: " + method,
                        TableWriterArchTest.tableWritesGoThroughTableWriterRule(VIOLATING),
                        DIRECT_WRITER + "." + method + "(")),
        Stream.of(
            row(
                "repositoryUpdateAndDeleteCheckVersion",
                TableWriterArchTest.repositoryUpdateAndDeleteCheckVersionRule(VIOLATING),
                UNVERSIONED_REPOSITORY + ".update("),
            row(
                "repositoryUpdateAndDeleteCheckVersion: save",
                TableWriterArchTest.repositoryUpdateAndDeleteCheckVersionRule(VIOLATING),
                UNVERSIONED_REPOSITORY + ".save("),
            row(
                "repositoryUpdateAndDeleteCheckVersion: re-read lockNo",
                TableWriterArchTest.repositoryUpdateAndDeleteCheckVersionRule(VIOLATING),
                UNVERSIONED_REPOSITORY + ".updateStatus("),
            row(
                "repositoryWritesTakeVersionedAggregates",
                TableWriterArchTest.repositoryWritesTakeVersionedAggregatesRule(),
                "order.domain.model.UnversionedOrderRepository.update("),
            row(
                "repositoryWritesTakeVersionedAggregates: add",
                TableWriterArchTest.repositoryWritesTakeVersionedAggregatesRule(),
                "order.domain.model.UnversionedOrderRepository.add("),
            row(
                "commandHandlersEnsureScreenLockNo",
                TableWriterArchTest.commandHandlersEnsureScreenLockNoRule(),
                "order.application.ApproveOrderCommandHandler"),
            row(
                "aggregateMethodsDoNotUseUnversionedWrites",
                TableWriterArchTest.aggregateMethodsDoNotUseUnversionedWritesRule(VIOLATING),
                UNVERSIONED_REPOSITORY + ".update(")));
  }

  /** クラスの役割と配置の規則が、対応する違反フィクスチャを検出することを確かめる行を作る。 */
  private static Stream<Arguments> classRoleRows() {
    return Stream.of(
        row(
            "dependenciesPointInward",
            PackageByFeatureOnionArchitectureTest.dependenciesPointInwardRule(VIOLATING),
            "order.infrastructure.messaging.OrderPlacedPublisher"),
        row(
            "infrastructureDependsOnlyOnDomainModel",
            PackageByFeatureOnionArchitectureTest.infrastructureDependsOnlyOnDomainModelRule(
                VIOLATING),
            "order.infrastructure.persistence.ExpiredOrderSweeper"),
        row(
            "sharedModuleIsUsedOnlyByPersistenceAdapters",
            PackageByFeatureOnionArchitectureTest.sharedModuleIsUsedOnlyByPersistenceAdaptersRule(
                VIOLATING),
            "order.application.OrderAuditColumns"),
        row(
            "moduleApiDoesNotExposeInternalTypes",
            PackageByFeatureOnionArchitectureTest.moduleApiDoesNotExposeInternalTypesRule(
                VIOLATING),
            "order.OrderPlacedWithModel"),
        row(
            "domainModelDoesNotDependOnFrameworks",
            PackageByFeatureOnionArchitectureTest.domainModelDoesNotDependOnFrameworks,
            "order.domain.model.OrderFactory"),
        row(
            "domainServicesDependOnlyOnDomainAndJava",
            PackageByFeatureOnionArchitectureTest.domainServicesDependOnlyOnDomainAndJavaRule(
                VIOLATING),
            "order.domain.service.DiscountPolicy"),
        row(
            "domainServicesAreAnnotatedWithService",
            PackageByFeatureOnionArchitectureTest.domainServicesAreAnnotatedWithService,
            "order.domain.service.ShippingFeeCalculator"),
        row(
            "servicesResideInApplicationOrDomainService",
            PackageByFeatureOnionArchitectureTest.servicesResideInApplicationOrDomainService,
            "order.infrastructure.client.PaymentService"),
        row(
            "controllersResideInPresentationWeb",
            PackageByFeatureOnionArchitectureTest.controllersResideInPresentationWeb,
            "order.presentation.OrderResource"),
        row(
            "presentationDoesNotDependOnDomain",
            PackageByFeatureOnionArchitectureTest.presentationDoesNotDependOnDomain,
            "order.presentation.web.OrderDetailsResponse"),
        row(
            "domainInterfacesAreImplementedInInfrastructure",
            PackageByFeatureOnionArchitectureTest
                .domainInterfacesAreImplementedInInfrastructureRule(VIOLATING),
            "order.application.InMemoryOrderRepository"),
        row(
            "repositoryImplementationsAreJooqRepositories",
            PackageByFeatureOnionArchitectureTest.repositoryImplementationsAreJooqRepositories,
            "order.application.InMemoryOrderRepository"),
        row(
            "externalSystemImplementationsAreClients",
            PackageByFeatureOnionArchitectureTest.externalSystemImplementationsAreClients,
            "order.infrastructure.client.PaymentGatewayAdapter"),
        row(
            "transactionalIsNotDeclaredAtClassLevel",
            PackageByFeatureOnionArchitectureTest.transactionalIsNotDeclaredAtClassLevel,
            "order.application.ShipOrderCommandHandler"),
        row(
            "transactionalMethodsArePublicApplicationMethods",
            PackageByFeatureOnionArchitectureTest.transactionalMethodsArePublicApplicationMethods,
            "order.infrastructure.persistence.OrderAuditRecorder"),
        row(
            "moduleListenersAreApplicationListeners",
            ClassRoleArchTest.moduleListenersAreApplicationListeners,
            "order.infrastructure.persistence.OrderAuditRecorder"),
        row(
            "moduleRootTypesAreRecordsEnumsOrQueries",
            ClassRoleArchTest.moduleRootTypesAreRecordsEnumsOrQueriesRule(VIOLATING),
            "order.OrderOperations"),
        row(
            "applicationServicesHaveRoleNames",
            ClassRoleArchTest.applicationServicesHaveRoleNames,
            "order.application.PlaceOrderService"),
        row(
            "commandHandlersExposeOnlyTransactionalHandle",
            ClassRoleArchTest.commandHandlersExposeOnlyTransactionalHandle,
            "order.application.CancelOrderCommandHandler"),
        row(
            "commandsAndResultsAreApplicationRecords",
            ClassRoleArchTest.commandsAndResultsAreApplicationRecordsRule(VIOLATING),
            "order.application.ConfirmOrderCommand"),
        row(
            "commandHandlersDoNotDependOnOtherCommandHandlers",
            ClassRoleArchTest.commandHandlersDoNotDependOnOtherCommandHandlers,
            "order.application.ConfirmOrderCommandHandler"),
        row(
            "listenersExposeOnlyOnAndCallOneCommandHandler",
            ClassRoleArchTest.listenersExposeOnlyOnAndCallOneCommandHandler,
            "inventory.application.OrderPlacedListener"),
        row(
            "requestsAndResponsesArePresentationWebRecords",
            ClassRoleArchTest.requestsAndResponsesArePresentationWebRecordsRule(VIOLATING),
            "order.presentation.web.PlaceOrderRequest"),
        row(
            "queryServicesImplementModuleQueries",
            ClassRoleArchTest.queryServicesImplementModuleQueries,
            "order.application.OrderQueryService"),
        row(
            "mappingLibrariesAreNotUsed",
            ClassRoleArchTest.mappingLibrariesAreNotUsed,
            REFLECTIVE_READER + ".defaultRecordMapper("),
        row(
            "mappingLibrariesAreNotUsed: DefaultRecordUnmapper",
            ClassRoleArchTest.mappingLibrariesAreNotUsed,
            REFLECTIVE_READER + ".defaultRecordUnmapper("),
        reflectionRow("recordInto"),
        reflectionRow("resultInto"),
        reflectionRow("fetchInto"),
        reflectionRow("fetchOneInto"),
        reflectionRow("fetchOptionalInto"),
        reflectionRow("fetchSingleInto"),
        reflectionRow("intoMap"),
        reflectionRow("intoGroups"),
        reflectionRow("fetchMap"),
        reflectionRow("fetchGroups"),
        reflectionRow("recordIntoObject"),
        reflectionRow("recordFrom"),
        reflectionRow("newRecordFromObject"));
  }

  /** {@code jooqReflectionMappingIsNotUsed} が、違反フィクスチャの同名のメソッドの呼び出しを検出することを確かめる行を作る。 */
  private static Arguments reflectionRow(final String method) {
    return row(
        "jooqReflectionMappingIsNotUsed: " + method,
        ClassRoleArchTest.jooqReflectionMappingIsNotUsed,
        REFLECTIVE_READER + "." + method + "(");
  }

  @Test
  @DisplayName("型安全な jOOQ の対応づけは対応づけの禁止規則に検出されない")
  void typeSafeJooqMappingIsAllowed() {
    final JavaClasses usage = new ClassFileImporter().importClasses(TypeSafeJooqMapping.class);

    assertThatCode(() -> ClassRoleArchTest.jooqReflectionMappingIsNotUsed.check(usage))
        .doesNotThrowAnyException();
    assertThatCode(() -> ClassRoleArchTest.mappingLibrariesAreNotUsed.check(usage))
        .doesNotThrowAnyException();
  }

  /**
   * 対応づけの禁止規則が許す jOOQ
   * の呼び出し（convertFrom、Records.mapping、into(Table)、fetch(RecordMapper)、列の型の変換）を持つフィクスチャ。
   */
  /* package */ static final class TypeSafeJooqMapping {

    /** 注文のテーブル。 */
    private static final Table<Record> ORDERS = DSL.table(DSL.name("orders"));

    /** 注文 ID の列。 */
    private static final Field<String> ORDER_ID = DSL.field(DSL.name("order_id"), String.class);

    private TypeSafeJooqMapping() {}

    /** 型を検査する対応づけで、注文 ID の値オブジェクトを読む。 */
    /* package */ static List<Object> read(final DSLContext dsl, final Record row) {
      return List.of(
          dsl.select(ORDER_ID.convertFrom(OrderNumber::new))
              .from(ORDERS)
              .fetch(Records.mapping(OrderNumber::value)),
          dsl.select(ORDER_ID.convertFrom(OrderNumber.class, OrderNumber::new))
              .from(ORDERS)
              .fetch(Record1::value1),
          row.into(ORDERS));
    }

    /** 列の型を {@code Class} で変換する呼び出しと、Object を受け取らない {@code newRecord} を使う。 */
    /* package */ static List<Object> convert(final DSLContext dsl, final Result<Record> rows) {
      return List.of(
          rows.intoArray(ORDER_ID, String.class),
          rows.intoSet(ORDER_ID, String.class),
          dsl.selectFrom(ORDERS).fetch(ORDER_ID, String.class),
          dsl.newRecord(ORDERS));
    }
  }

  /** フィクスチャが列を変換する先の値オブジェクト。 */
  private record OrderNumber(String value) {}

  private static Arguments row(
      final String ruleName, final ArchRule rule, final String violatingClass) {
    return Arguments.of(ruleName, rule, VIOLATING_PREFIX + violatingClass);
  }

  /** 検査クラスの全規則を、指定した基底パッケージで組み立てて返す。 */
  private static List<ArchRule> rulesFor(final String basePackage) {
    return List.of(
        PackageByFeatureOnionArchitectureTest.dependenciesPointInwardRule(basePackage),
        PackageByFeatureOnionArchitectureTest.infrastructureDependsOnlyOnDomainModelRule(
            basePackage),
        PackageByFeatureOnionArchitectureTest.sharedModuleIsUsedOnlyByPersistenceAdaptersRule(
            basePackage),
        PackageByFeatureOnionArchitectureTest.moduleApiDoesNotExposeInternalTypesRule(basePackage),
        PackageByFeatureOnionArchitectureTest
            .databaseTechnologyApisAreOnlyUsedByPersistenceAdapters,
        PackageByFeatureOnionArchitectureTest.domainModelDoesNotDependOnFrameworks,
        PackageByFeatureOnionArchitectureTest.domainServicesDependOnlyOnDomainAndJavaRule(
            basePackage),
        PackageByFeatureOnionArchitectureTest.domainServicesAreAnnotatedWithService,
        PackageByFeatureOnionArchitectureTest.servicesResideInApplicationOrDomainService,
        PackageByFeatureOnionArchitectureTest.controllersResideInPresentationWeb,
        PackageByFeatureOnionArchitectureTest.repositoriesResideInPersistenceAdapters,
        PackageByFeatureOnionArchitectureTest.presentationDoesNotDependOnDomain,
        PackageByFeatureOnionArchitectureTest.domainInterfacesAreImplementedInInfrastructureRule(
            basePackage),
        PackageByFeatureOnionArchitectureTest.repositoryImplementationsAreJooqRepositories,
        PackageByFeatureOnionArchitectureTest.externalSystemImplementationsAreClients,
        PackageByFeatureOnionArchitectureTest.transactionalIsNotDeclaredAtClassLevel,
        PackageByFeatureOnionArchitectureTest.transactionalMethodsArePublicApplicationMethods,
        ClassRoleArchTest.moduleRootTypesAreRecordsEnumsOrQueriesRule(basePackage),
        ClassRoleArchTest.applicationServicesHaveRoleNames,
        ClassRoleArchTest.commandHandlersExposeOnlyTransactionalHandle,
        ClassRoleArchTest.commandsAndResultsAreApplicationRecordsRule(basePackage),
        ClassRoleArchTest.commandHandlersDoNotDependOnOtherCommandHandlers,
        ClassRoleArchTest.moduleListenersAreApplicationListeners,
        ClassRoleArchTest.listenersExposeOnlyOnAndCallOneCommandHandler,
        ClassRoleArchTest.requestsAndResponsesArePresentationWebRecordsRule(basePackage),
        ClassRoleArchTest.queryServicesImplementModuleQueries,
        ClassRoleArchTest.mappingLibrariesAreNotUsed,
        ClassRoleArchTest.jooqReflectionMappingIsNotUsed,
        TableWriterArchTest.tableWritesGoThroughTableWriterRule(basePackage),
        TableWriterArchTest.repositoryUpdateAndDeleteCheckVersionRule(basePackage),
        TableWriterArchTest.repositoryWritesTakeVersionedAggregatesRule(),
        TableWriterArchTest.commandHandlersEnsureScreenLockNoRule(),
        TableWriterArchTest.aggregateMethodsDoNotUseUnversionedWritesRule(basePackage));
  }
}
