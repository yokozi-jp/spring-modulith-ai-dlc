package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.failure.BusinessRuleViolationException;
import com.example.demo.shared.failure.NotFoundException;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.Optional;
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
  private static final String DIRECT_WRITER =
      "ordering.infrastructure.persistence.DirectOrderWriter";

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
      "ordering.infrastructure.persistence.JooqOrderRepository";

  /** リフレクションで対応づける違反フィクスチャのクラス名。 */
  private static final String REFLECTIVE_READER =
      "ordering.infrastructure.persistence.ReflectiveOrderReader";

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

  /** TableWriter の規則（H1、H3、H5、H6、R1 から R4）が、対応する違反フィクスチャを検出することを確かめる行を作る。 */
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
                "ordering.domain.model.UnversionedOrderRepository.update("),
            row(
                "repositoryWritesTakeVersionedAggregates: add",
                TableWriterArchTest.repositoryWritesTakeVersionedAggregatesRule(),
                "ordering.domain.model.UnversionedOrderRepository.add("),
            row(
                "commandHandlersEnsureScreenLockNo: unused private method",
                TableWriterArchTest.commandHandlersEnsureScreenLockNoRule(VIOLATING),
                "ordering.application.ApproveOrderCommandHandler.handle("),
            row(
                "commandHandlersEnsureScreenLockNo: ensureLockNo(long) overload",
                TableWriterArchTest.commandHandlersEnsureScreenLockNoRule(VIOLATING),
                "ordering.application.OverloadedEnsureCommandHandler"),
            row(
                "commandsBuiltByPresentationForWritesAreVersioned",
                TableWriterArchTest.commandsBuiltByPresentationForWritesAreVersionedRule(VIOLATING),
                "ordering.application.ReleaseOrderCommandHandler"),
            row(
                "commandsBuiltByPresentationForWritesAreVersioned: static factory call",
                TableWriterArchTest.commandsBuiltByPresentationForWritesAreVersionedRule(VIOLATING),
                "ordering.application.SuspendOrderCommandHandler"),
            row(
                "commandsBuiltByPresentationForWritesAreVersioned: static factory reference",
                TableWriterArchTest.commandsBuiltByPresentationForWritesAreVersionedRule(VIOLATING),
                "ordering.application.ResumeOrderCommandHandler"),
            row(
                "commandsBuiltByPresentationForWritesAreVersioned: save",
                TableWriterArchTest.commandsBuiltByPresentationForWritesAreVersionedRule(VIOLATING),
                "ordering.application.ArchiveOrderCommandHandler"),
            row(
                "onlyCommandHandlersUpdateOrDeleteAggregates: save",
                TableWriterArchTest.onlyCommandHandlersUpdateOrDeleteAggregatesRule(),
                "ordering.domain.service.ReopenPolicy.reopenAggregate("),
            row(
                "onlyCommandHandlersUpdateOrDeleteAggregates: Repository implementation",
                TableWriterArchTest.onlyCommandHandlersUpdateOrDeleteAggregatesRule(),
                UNVERSIONED_REPOSITORY
                    + ".save(archfixture.violating.ordering.domain.model.OrderId)"),
            row(
                "onlyCommandHandlersUpdateOrDeleteAggregates: Domain Service",
                TableWriterArchTest.onlyCommandHandlersUpdateOrDeleteAggregatesRule(),
                "ordering.domain.service.ReopenPolicy.reopen("),
            row(
                "onlyCommandHandlersUpdateOrDeleteAggregates: QueryService",
                TableWriterArchTest.onlyCommandHandlersUpdateOrDeleteAggregatesRule(),
                "ordering.application.PurgeOrderQueryService.purge("),
            row(
                "expectedLockNoIsCreatedOnlyByRequests: constructor",
                TableWriterArchTest.expectedLockNoIsCreatedOnlyByRequestsRule(VIOLATING),
                "ordering.application.ForgedLockNoCommandHandler.forged("),
            row(
                "expectedLockNoIsCreatedOnlyByRequests: constructor reference",
                TableWriterArchTest.expectedLockNoIsCreatedOnlyByRequestsRule(VIOLATING),
                "ordering.application.ForgedLockNoCommandHandler.forgedByReference("),
            row(
                "expectedLockNoIsCreatedOnlyByRequests: Controller",
                TableWriterArchTest.expectedLockNoIsCreatedOnlyByRequestsRule(VIOLATING),
                "ordering.presentation.web.OrderLockController.lockNo("),
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
            "ordering.infrastructure.messaging.OrderPlacedPublisher"),
        row(
            "infrastructureDependsOnlyOnDomainModel",
            PackageByFeatureOnionArchitectureTest.infrastructureDependsOnlyOnDomainModelRule(
                VIOLATING),
            "ordering.infrastructure.persistence.ExpiredOrderSweeper"),
        row(
            "sharedModuleIsUsedOnlyByPersistenceAdapters",
            PackageByFeatureOnionArchitectureTest.sharedModuleIsUsedOnlyByPersistenceAdaptersRule(
                VIOLATING),
            "ordering.application.OrderAuditColumns"),
        row(
            "sharedModuleDoesNotDependOnHttp",
            PackageByFeatureOnionArchitectureTest.sharedModuleDoesNotDependOnHttpRule(VIOLATING),
            "shared.infrastructure.persistence.ChildRowWriter"),
        row(
            "noSuchElementExceptionIsNotThrown",
            GeneralCodingRulesArchTest.noSuchElementExceptionIsNotThrown,
            "ordering.application.LegacyOrderFinder.notFound("),
        row(
            "noSuchElementExceptionIsNotThrown: orElseThrow",
            GeneralCodingRulesArchTest.noSuchElementExceptionIsNotThrown,
            "ordering.application.LegacyOrderFinder.firstOrFail("),
        row(
            "moduleApiDoesNotExposeInternalTypes",
            PackageByFeatureOnionArchitectureTest.moduleApiDoesNotExposeInternalTypesRule(
                VIOLATING),
            "ordering.OrderPlacedWithModel"),
        row(
            "domainModelDoesNotDependOnFrameworks",
            PackageByFeatureOnionArchitectureTest.domainModelDoesNotDependOnFrameworks,
            "ordering.domain.model.OrderFactory"),
        row(
            "domainServicesDependOnlyOnDomainAndJava",
            PackageByFeatureOnionArchitectureTest.domainServicesDependOnlyOnDomainAndJavaRule(
                VIOLATING),
            "ordering.domain.service.DiscountPolicy"),
        row(
            "domainServicesAreAnnotatedWithService",
            PackageByFeatureOnionArchitectureTest.domainServicesAreAnnotatedWithService,
            "ordering.domain.service.ShippingFeeCalculator"),
        row(
            "servicesResideInApplicationOrDomainService",
            PackageByFeatureOnionArchitectureTest.servicesResideInApplicationOrDomainService,
            "ordering.infrastructure.client.PaymentService"),
        row(
            "controllersResideInPresentationWeb",
            PackageByFeatureOnionArchitectureTest.controllersResideInPresentationWeb,
            "ordering.presentation.OrderResource"),
        row(
            "presentationDoesNotDependOnDomain",
            PackageByFeatureOnionArchitectureTest.presentationDoesNotDependOnDomain,
            "ordering.presentation.web.OrderDetailsResponse"),
        row(
            "domainInterfacesAreImplementedInInfrastructure",
            PackageByFeatureOnionArchitectureTest
                .domainInterfacesAreImplementedInInfrastructureRule(VIOLATING),
            "ordering.application.InMemoryOrderRepository"),
        row(
            "repositoryImplementationsAreJooqRepositories",
            PackageByFeatureOnionArchitectureTest.repositoryImplementationsAreJooqRepositories,
            "ordering.application.InMemoryOrderRepository"),
        row(
            "externalSystemImplementationsAreClients",
            PackageByFeatureOnionArchitectureTest.externalSystemImplementationsAreClients,
            "ordering.infrastructure.client.PaymentGatewayAdapter"),
        row(
            "transactionalIsNotDeclaredAtClassLevel",
            PackageByFeatureOnionArchitectureTest.transactionalIsNotDeclaredAtClassLevel,
            "ordering.application.ShipOrderCommandHandler"),
        row(
            "transactionalMethodsArePublicApplicationMethods",
            PackageByFeatureOnionArchitectureTest.transactionalMethodsArePublicApplicationMethods,
            "ordering.infrastructure.persistence.OrderAuditRecorder"),
        row(
            "moduleListenersAreApplicationListeners",
            ClassRoleArchTest.moduleListenersAreApplicationListeners,
            "ordering.infrastructure.persistence.OrderAuditRecorder"),
        row(
            "moduleRootTypesAreRecordsEnumsOrQueries",
            ClassRoleArchTest.moduleRootTypesAreRecordsEnumsOrQueriesRule(VIOLATING),
            "ordering.OrderOperations"),
        row(
            "applicationServicesHaveRoleNames",
            ClassRoleArchTest.applicationServicesHaveRoleNames,
            "ordering.application.PlaceOrderService"),
        row(
            "commandHandlersExposeOnlyTransactionalHandle",
            ClassRoleArchTest.commandHandlersExposeOnlyTransactionalHandle,
            "ordering.application.CancelOrderCommandHandler"),
        row(
            "commandsAndResultsAreApplicationRecords",
            ClassRoleArchTest.commandsAndResultsAreApplicationRecordsRule(VIOLATING),
            "ordering.application.ConfirmOrderCommand"),
        row(
            "commandsAndResultsAreApplicationRecords: shared.concurrency",
            ClassRoleArchTest.commandsAndResultsAreApplicationRecordsRule(VIOLATING),
            "shared.concurrency.ForceUnlockCommand"),
        row(
            "commandHandlersDoNotDependOnOtherCommandHandlers",
            ClassRoleArchTest.commandHandlersDoNotDependOnOtherCommandHandlers,
            "ordering.application.ConfirmOrderCommandHandler"),
        row(
            "listenersExposeOnlyOnAndCallOneCommandHandler",
            ClassRoleArchTest.listenersExposeOnlyOnAndCallOneCommandHandler,
            "inventory.application.OrderPlacedListener"),
        row(
            "requestsAndResponsesArePresentationWebRecords",
            ClassRoleArchTest.requestsAndResponsesArePresentationWebRecordsRule(VIOLATING),
            "ordering.presentation.web.PlaceOrderRequest"),
        row(
            "queryServicesImplementModuleQueries",
            ClassRoleArchTest.queryServicesImplementModuleQueries,
            "ordering.application.OrderQueryService"),
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

  @Test
  @DisplayName("プログラムの誤りの例外と業務上の失敗の例外は NoSuchElementException の禁止規則に検出されない")
  void programmingErrorsAreAllowed() {
    final JavaClasses usage = new ClassFileImporter().importClasses(ProgrammingErrors.class);

    assertThatCode(() -> GeneralCodingRulesArchTest.noSuchElementExceptionIsNotThrown.check(usage))
        .doesNotThrowAnyException();
  }

  /** NoSuchElementException の禁止規則が許す例外の送出と {@code orElseThrow(Supplier)} を持つフィクスチャ。 */
  /* package */ static final class ProgrammingErrors {

    private ProgrammingErrors() {}

    /** 空のコードを IllegalArgumentException で拒否する。 */
    /* package */ static void requireCode(final String code) {
      if (code.isBlank()) {
        throw new IllegalArgumentException("code must not be blank");
      }
    }

    /** 主キーの条件が 1 行を特定しなければ IllegalStateException を投げる。 */
    /* package */ static void requireSingleRow(final boolean single) {
      if (!single) {
        throw new IllegalStateException("primary key condition matched several rows");
      }
    }

    /** 受付でない注文を BusinessRuleViolationException で拒否する。 */
    /* package */ static void requirePlaced(final boolean placed) {
      if (!placed) {
        throw new BusinessRuleViolationException("order is not placed");
      }
    }

    /** 版が違えば競合の例外を投げる。 */
    /* package */ static void ensureLockNo(final long expected, final long actual) {
      if (expected != actual) {
        throw new ConflictException(
            ConflictException.Kind.VERSION, "row was updated by another request");
      }
    }

    /** 空の Optional から、Supplier で作った NotFoundException を投げる。 */
    /* package */ static String notFound(final Optional<String> order) {
      return order.orElseThrow(() -> new NotFoundException("order not found: orderId=1"));
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
        PackageByFeatureOnionArchitectureTest.sharedModuleDoesNotDependOnHttpRule(basePackage),
        PackageByFeatureOnionArchitectureTest.moduleApiDoesNotExposeInternalTypesRule(basePackage),
        PackageByFeatureOnionArchitectureTest
            .databaseTechnologyApisAreOnlyUsedByPersistenceAdapters,
        GeneralCodingRulesArchTest.noSuchElementExceptionIsNotThrown,
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
        TableWriterArchTest.commandHandlersEnsureScreenLockNoRule(basePackage),
        TableWriterArchTest.commandsBuiltByPresentationForWritesAreVersionedRule(basePackage),
        TableWriterArchTest.onlyCommandHandlersUpdateOrDeleteAggregatesRule(),
        TableWriterArchTest.expectedLockNoIsCreatedOnlyByRequestsRule(basePackage),
        TableWriterArchTest.aggregateMethodsDoNotUseUnversionedWritesRule(basePackage));
  }
}
