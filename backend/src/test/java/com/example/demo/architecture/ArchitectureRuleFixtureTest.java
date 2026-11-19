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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * クラスの役割と配置の ArchUnit 規則が、空振りせずに違反を検出することを確かめる。
 *
 * <p>プロダクションコードにはまだ機能モジュールがなく、規則の多くは {@code allowEmptyShould(true)} で空のまま通る。 そこで {@code
 * com.example.demo} の外に置いたテスト専用のフィクスチャを import し、規約どおりの {@code archfixture.conforming}
 * がすべての規則を満たすこと、{@code archfixture.violating} の各クラスが対応する規則で検出されることを確かめる。
 */
// JavaClasses は ArchUnit の import 結果を表す公開 API 型であり、インタフェースへ置き換えられない。
// 誤検出しないことを規則の組ごとに別のテストで確かめるため、メソッドの数は確かめる組の数に比例する。
@SuppressWarnings({"PMD.LooseCoupling", "PMD.TooManyMethods"})
class ArchitectureRuleFixtureTest {

  /** 規約どおりのフィクスチャの基底パッケージ。 */
  private static final String CONFORMING = "archfixture.conforming";

  /** 違反フィクスチャの基底パッケージ。 */
  private static final String VIOLATING = "archfixture.violating";

  /** 違反フィクスチャのクラス名の接頭辞。 */
  private static final String VIOLATING_PREFIX = VIOLATING + ".";

  /**
   * 違反フィクスチャの import 結果。
   *
   * <p>import はクラスパス全体を走査して遅く、パラメータごとに繰り返すと全体の大半を占める。規則の検査は import 結果を読むだけなので、全パラメータで共有する。
   */
  private static final JavaClasses VIOLATING_CLASSES =
      new ClassFileImporter().importPackages(VIOLATING);

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

  /** ロールとスキーマを持つ機能モジュールの、フィクスチャでの名前。 */
  private static final Set<String> FIXTURE_MODULES = Set.of("ordering", "inventory");

  /** jOOQ の生成クラスの基底パッケージ。フィクスチャは本物の生成クラスを使う。 */
  private static final String JOOQ_PACKAGE = "com.example.demo.jooq";

  /** モジュールのロールを迂回する違反フィクスチャのクラス名。 */
  private static final String ROLE_BYPASS =
      "ordering.infrastructure.persistence.RoleBypassingOrderAccess";

  /** モジュールのロールの規則が、違反フィクスチャの各メソッドを検出することを確かめる行。 */
  private static final List<Arguments> MODULE_ROLE_ROWS =
      List.of(
          row(
              "persistenceAdaptersUseOnlyOwnGeneratedSchema",
              DatabaseConventionsArchTest.ownGeneratedSchemaRule(
                  VIOLATING, JOOQ_PACKAGE, FIXTURE_MODULES),
              ROLE_BYPASS),
          row(
              "moduleCodeDoesNotUseJdbc: JdbcTemplate",
              DatabaseConventionsArchTest.moduleCodeDoesNotUseJdbcRule(VIOLATING, FIXTURE_MODULES),
              ROLE_BYPASS + ".jdbcTemplateQuery("),
          row(
              "moduleCodeDoesNotUseJdbc: DataSource",
              DatabaseConventionsArchTest.moduleCodeDoesNotUseJdbcRule(VIOLATING, FIXTURE_MODULES),
              ROLE_BYPASS + ".rawConnection("),
          row(
              "moduleCodeDoesNotChangeConnectionSettings: set",
              DatabaseConventionsArchTest.moduleCodeDoesNotChangeConnectionSettingsRule(
                  VIOLATING, FIXTURE_MODULES),
              ROLE_BYPASS + ".dslSet("),
          row(
              "moduleCodeDoesNotChangeConnectionSettings: setLocal",
              DatabaseConventionsArchTest.moduleCodeDoesNotChangeConnectionSettingsRule(
                  VIOLATING, FIXTURE_MODULES),
              ROLE_BYPASS + ".dslSetLocal("),
          row(
              "moduleCodeDoesNotChangeConnectionSettings: DSL.setLocal",
              DatabaseConventionsArchTest.moduleCodeDoesNotChangeConnectionSettingsRule(
                  VIOLATING, FIXTURE_MODULES),
              ROLE_BYPASS + ".staticSetLocal("),
          row(
              "moduleCodeDoesNotChangeConnectionSettings: setSchema",
              DatabaseConventionsArchTest.moduleCodeDoesNotChangeConnectionSettingsRule(
                  VIOLATING, FIXTURE_MODULES),
              ROLE_BYPASS + ".dslSetSchema("),
          row(
              "moduleCodeDoesNotChangeConnectionSettings: connectionResult",
              DatabaseConventionsArchTest.moduleCodeDoesNotChangeConnectionSettingsRule(
                  VIOLATING, FIXTURE_MODULES),
              ROLE_BYPASS + ".dslConnectionResult("));

  /** 数値の ID の規則が、違反フィクスチャの各パッケージの違反を検出することを確かめる行。 */
  private static final List<Arguments> PUBLIC_ID_ROWS =
      List.of(
          row(
              "numericIdsDoNotLeaveModules: event",
              PublicIdArchTest.numericIdsDoNotLeaveModulesRule(VIOLATING),
              "ordering.OrderShipped.orderId"),
          row(
              "numericIdsDoNotLeaveModules: public API parameter",
              PublicIdArchTest.numericIdsDoNotLeaveModulesRule(VIOLATING),
              "ordering.OrderLookupQueries.findById(long) の引数 orderId"),
          row(
              "numericIdsDoNotLeaveModules: public API return type",
              PublicIdArchTest.numericIdsDoNotLeaveModulesRule(VIOLATING),
              "ordering.OrderLookupQueries.findOrderId(java.lang.String) は数値の ID を返す"),
          row(
              "numericIdsDoNotLeaveModules: generic parameter",
              PublicIdArchTest.numericIdsDoNotLeaveModulesRule(VIOLATING),
              "ordering.OrderLookupQueries.findByIds(java.util.List) の引数 orderIds"),
          row(
              "numericIdsDoNotLeaveModules: generic return type",
              PublicIdArchTest.numericIdsDoNotLeaveModulesRule(VIOLATING),
              "ordering.OrderLookupQueries.findLatestOrderId(java.lang.String) は数値の ID を返す"),
          row(
              "numericIdsDoNotLeaveModules: array return type",
              PublicIdArchTest.numericIdsDoNotLeaveModulesRule(VIOLATING),
              "ordering.OrderLookupQueries.findLineIds(java.lang.String) は数値の ID を返す"),
          row(
              "numericIdsDoNotLeaveModules: Domain id",
              PublicIdArchTest.numericIdsDoNotLeaveModulesRule(VIOLATING),
              "ordering.domain.model.Shipment.id"),
          row(
              "numericIdsDoNotLeaveModules: Application",
              PublicIdArchTest.numericIdsDoNotLeaveModulesRule(VIOLATING),
              "ordering.application.RefundOrderCommand.orderId"),
          row(
              "numericIdsDoNotLeaveModules: external system",
              PublicIdArchTest.numericIdsDoNotLeaveModulesRule(VIOLATING),
              "ordering.infrastructure.client.ShippingLabelClient.print(int) の引数 orderId"),
          row(
              "numericIdsDoNotLeaveModules: shared",
              PublicIdArchTest.numericIdsDoNotLeaveModulesRule(VIOLATING),
              "shared.concurrency.LockRequest.rowId"));

  /** 版を比べない入口を使う違反フィクスチャの Repository。 */
  private static final String UNVERSIONED_REPOSITORY =
      "ordering.infrastructure.persistence.JooqOrderRepository";

  /** リフレクションで対応づける違反フィクスチャのクラス名。 */
  private static final String REFLECTIVE_READER =
      "ordering.infrastructure.persistence.ReflectiveOrderReader";

  /** プロキシが上書きできないメソッドにアノテーションを付けた違反フィクスチャのクラス名。 */
  private static final String ORDER_ARCHIVER = "ordering.application.OrderArchiver";

  /** {@code @Scheduled} のメソッドの形に違反するフィクスチャのクラス名。 */
  private static final String STALE_LOCK_SWEEPER =
      "shared.infrastructure.persistence.StaleLockSweeper";

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
    assertThatThrownBy(() -> rule.check(VIOLATING_CLASSES))
        .as(ruleName)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(violatingClass);
  }

  private static Stream<Arguments> eachRuleDetectsItsViolatingFixture() {
    return Stream.of(
            classRoleRows(), tableWriterRows(), PUBLIC_ID_ROWS.stream(), MODULE_ROLE_ROWS.stream())
        .flatMap(rows -> rows);
  }

  /** TableWriter の規則（H1、H3、H5、H6、H7、R1 から R4）が、対応する違反フィクスチャを検出することを確かめる行を作る。 */
  private static Stream<Arguments> tableWriterRows() {
    return Stream.concat(
        DIRECT_WRITES.stream()
            .map(method ->
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
                UNVERSIONED_REPOSITORY + ".update("),
            row(
                "aggregateRootInsertsGoThroughTableWriter",
                TableWriterArchTest.aggregateRootInsertsGoThroughTableWriterRule(),
                "ordering.infrastructure.persistence.JooqOrderDraftRepository.add(")));
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
            "commandHandlersAndListenersDoNotUseInheritance: 役割名でないサブクラス",
            ClassRoleArchTest.commandHandlersAndListenersDoNotUseInheritance,
            "ordering.application.ReopenOrderHandler"),
        row(
            "commandHandlersAndListenersDoNotUseInheritance: 基底クラス",
            ClassRoleArchTest.commandHandlersAndListenersDoNotUseInheritance,
            "ordering.application.OrderHandlerSupport"),
        row(
            "commandHandlersAndListenersDoNotUseInheritance: インタフェースの実装",
            ClassRoleArchTest.commandHandlersAndListenersDoNotUseInheritance,
            "ordering.application.OrderShippedReceiver"),
        row(
            "pointcutsNarrowTargetsWithWithin: value",
            ProxyRulesArchTest.pointcutsNarrowTargetsWithWithin,
            "shared.infrastructure.persistence.SlowPgmCdAspect.bind("),
        row(
            "pointcutsNarrowTargetsWithWithin: pointcut",
            ProxyRulesArchTest.pointcutsNarrowTargetsWithWithin,
            "shared.infrastructure.persistence.SlowPgmCdAspect.afterListener("),
        row(
            "aspectsDeclareOrder: @Order がない",
            ProxyRulesArchTest.aspectsDeclareOrder,
            "shared.infrastructure.persistence.UnorderedPgmCdAspect"),
        row(
            "aspectsDeclareOrder: 値のない @Order",
            ProxyRulesArchTest.aspectsDeclareOrder,
            "shared.infrastructure.persistence.LowestOrderPgmCdAspect"),
        row(
            "transactionManagementIsNotRedeclared",
            ProxyRulesArchTest.transactionManagementIsNotRedeclared,
            "shared.infrastructure.persistence.TransactionOrderConfiguration"),
        row(
            "proxiedMethodsAreNotPrivateFinalOrStatic: final",
            ProxyRulesArchTest.proxiedMethodsAreNotPrivateFinalOrStatic,
            ORDER_ARCHIVER + ".archive(java.lang.String) は final で、プロキシが @Transactional"),
        row(
            "proxiedMethodsAreNotPrivateFinalOrStatic: static",
            ProxyRulesArchTest.proxiedMethodsAreNotPrivateFinalOrStatic,
            ORDER_ARCHIVER + ".normalize(java.lang.String) は static で、プロキシが @PreAuthorize"),
        row(
            "proxiedMethodsAreNotPrivateFinalOrStatic: private",
            ProxyRulesArchTest.proxiedMethodsAreNotPrivateFinalOrStatic,
            ORDER_ARCHIVER + ".retryArchive() は private で、プロキシが @Retryable"),
        row(
            "proxiedMethodsAreNotPrivateFinalOrStatic: 合成アノテーション",
            ProxyRulesArchTest.proxiedMethodsAreNotPrivateFinalOrStatic,
            ORDER_ARCHIVER
                + ".on(archfixture.violating.ordering.OrderPlaced) は final で、"
                + "プロキシが @ApplicationModuleListener"),
        row(
            "asyncMethodsReturnVoidOrFuture",
            ProxyRulesArchTest.asyncMethodsReturnVoidOrFuture,
            "ordering.application.OrderNotifier.notifyShipped("),
        row(
            "scheduledMethodsAreOverridableNoArgVoidMethods: 戻り値",
            ProxyRulesArchTest.scheduledMethodsAreOverridableNoArgVoidMethods,
            STALE_LOCK_SWEEPER + ".sweep("),
        row(
            "scheduledMethodsAreOverridableNoArgVoidMethods: 引数",
            ProxyRulesArchTest.scheduledMethodsAreOverridableNoArgVoidMethods,
            STALE_LOCK_SWEEPER + ".sweepOlderThan("),
        row(
            "scheduledMethodsAreOverridableNoArgVoidMethods: private",
            ProxyRulesArchTest.scheduledMethodsAreOverridableNoArgVoidMethods,
            STALE_LOCK_SWEEPER + ".sweepPrivately("),
        row(
            "scheduledMethodsAreOverridableNoArgVoidMethods: final",
            ProxyRulesArchTest.scheduledMethodsAreOverridableNoArgVoidMethods,
            STALE_LOCK_SWEEPER + ".sweepFinally("),
        row(
            "scheduledMethodsAreOverridableNoArgVoidMethods: 繰り返した @Scheduled",
            ProxyRulesArchTest.scheduledMethodsAreOverridableNoArgVoidMethods,
            STALE_LOCK_SWEEPER + ".sweepTwice("),
        row(
            "eventListenersAreApplicationModuleListeners: @EventListener",
            ClassRoleArchTest.eventListenersAreApplicationModuleListeners,
            "inventory.application.StockEventReceiver.onPlaced("),
        row(
            "eventListenersAreApplicationModuleListeners: @TransactionalEventListener",
            ClassRoleArchTest.eventListenersAreApplicationModuleListeners,
            "inventory.application.StockEventReceiver.afterPlaced("),
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
            "queryResultStatusIsNotReadByOtherModules: status()",
            ClassRoleArchTest.queryResultStatusIsNotReadByOtherModulesRule(VIOLATING),
            "inventory.application.OrderStatusGuard.isConfirmed("),
        row(
            "queryResultStatusIsNotReadByOtherModules: *Status method reference",
            ClassRoleArchTest.queryResultStatusIsNotReadByOtherModulesRule(VIOLATING),
            "inventory.application.OrderStatusGuard.paidStatuses("),
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

  @Test
  @DisplayName("プロキシが上書きできるメソッドと、Spring が受け付ける @Async と @Scheduled の形は、規則に検出されない")
  void interceptableMethodShapesAreAllowed() {
    final JavaClasses usage = new ClassFileImporter().importClasses(InterceptableMethods.class);

    for (final ArchRule rule :
        List.of(
            ProxyRulesArchTest.proxiedMethodsAreNotPrivateFinalOrStatic,
            ProxyRulesArchTest.asyncMethodsReturnVoidOrFuture,
            ProxyRulesArchTest.scheduledMethodsAreOverridableNoArgVoidMethods)) {
      assertThatCode(() -> rule.check(usage)).doesNotThrowAnyException();
    }
  }

  /**
   * プロキシの規則が許すメソッドを持つフィクスチャ。
   *
   * <p>CGLIB のプロキシは package-private のメソッドも上書きし、Spring は static の {@code @Scheduled} をプロキシの Bean
   * でも呼べる。
   */
  /* package */ static class InterceptableMethods {

    /** 書き込んだ注文 ID。 */
    private final List<String> written = new CopyOnWriteArrayList<>();

    /** package-private の {@code @Transactional} で注文 ID を書き込む。 */
    @Transactional
    /* package */ void archive(final String orderId) {
      written.add(orderId);
    }

    /** void の {@code @Async} で出荷を通知する。 */
    @Async
    /* package */ void notifyShipped(final String orderId) {
      written.add(orderId);
    }

    /** {@code CompletableFuture} を返す {@code @Async} で見積もる。 */
    @Async
    /* package */ CompletableFuture<String> quote(final String orderId) {
      return CompletableFuture.completedFuture(orderId);
    }

    /** {@code Future} を返す {@code @Async} で見積もる。 */
    @Async
    /* package */ Future<String> estimate(final String orderId) {
      return CompletableFuture.completedFuture(orderId);
    }

    /** package-private で引数のない void の {@code @Scheduled} で掃除する。 */
    @Scheduled(fixedDelay = 60_000)
    /* package */ void sweep() {
      written.clear();
    }

    /** static の {@code @Scheduled} で掃除する。 */
    @Scheduled(fixedDelay = 60_000)
    /* package */ static void sweepStatically() {
      // static の @Scheduled が許されることだけを確かめるため、何もしない。
    }

    /** アノテーションのない static のメソッドで注文 ID を正規化する。 */
    /* package */ static String normalize(final String orderId) {
      return orderId.trim();
    }

    /** アノテーションのない final のメソッドで最後の注文 ID を返す。 */
    /* package */ final String last() {
      return written.getLast();
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
        ClassRoleArchTest.commandHandlersAndListenersDoNotUseInheritance,
        ProxyRulesArchTest.pointcutsNarrowTargetsWithWithin,
        ProxyRulesArchTest.aspectsDeclareOrder,
        ProxyRulesArchTest.transactionManagementIsNotRedeclared,
        ProxyRulesArchTest.proxiedMethodsAreNotPrivateFinalOrStatic,
        ProxyRulesArchTest.asyncMethodsReturnVoidOrFuture,
        ProxyRulesArchTest.scheduledMethodsAreOverridableNoArgVoidMethods,
        ClassRoleArchTest.moduleListenersAreApplicationListeners,
        ClassRoleArchTest.eventListenersAreApplicationModuleListeners,
        ClassRoleArchTest.listenersExposeOnlyOnAndCallOneCommandHandler,
        ClassRoleArchTest.requestsAndResponsesArePresentationWebRecordsRule(basePackage),
        ClassRoleArchTest.queryServicesImplementModuleQueries,
        ClassRoleArchTest.queryResultStatusIsNotReadByOtherModulesRule(basePackage),
        ClassRoleArchTest.mappingLibrariesAreNotUsed,
        ClassRoleArchTest.jooqReflectionMappingIsNotUsed,
        TableWriterArchTest.tableWritesGoThroughTableWriterRule(basePackage),
        TableWriterArchTest.repositoryUpdateAndDeleteCheckVersionRule(basePackage),
        TableWriterArchTest.repositoryWritesTakeVersionedAggregatesRule(),
        TableWriterArchTest.commandHandlersEnsureScreenLockNoRule(basePackage),
        TableWriterArchTest.commandsBuiltByPresentationForWritesAreVersionedRule(basePackage),
        TableWriterArchTest.onlyCommandHandlersUpdateOrDeleteAggregatesRule(),
        TableWriterArchTest.expectedLockNoIsCreatedOnlyByRequestsRule(basePackage),
        TableWriterArchTest.aggregateMethodsDoNotUseUnversionedWritesRule(basePackage),
        TableWriterArchTest.aggregateRootInsertsGoThroughTableWriterRule(),
        PublicIdArchTest.numericIdsDoNotLeaveModulesRule(basePackage),
        DatabaseConventionsArchTest.ownGeneratedSchemaRule(
            basePackage, JOOQ_PACKAGE, FIXTURE_MODULES),
        DatabaseConventionsArchTest.moduleCodeDoesNotUseJdbcRule(basePackage, FIXTURE_MODULES),
        DatabaseConventionsArchTest.moduleCodeDoesNotChangeConnectionSettingsRule(
            basePackage, FIXTURE_MODULES));
  }
}
