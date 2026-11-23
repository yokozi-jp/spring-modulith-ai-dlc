package com.example.demo.architecture;

import static com.example.demo.architecture.ViolatingFixtures.VIOLATING;
import static com.example.demo.architecture.ViolatingFixtures.row;

import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/** {@link ArchitectureRuleFixtureTest} が使う、クラスの役割と配置の規則と、それを検出する違反フィクスチャの対応の行。 */
final class ClassRoleFixtureRows {

  /** リフレクションで対応づける違反フィクスチャのクラス名。 */
  private static final String REFLECTIVE_READER =
      "ordering.infrastructure.persistence.ReflectiveOrderReader";

  /** クラスの役割と配置の規則が、対応する違反フィクスチャを検出することを確かめる行を作る。 */
  /* package */ static Stream<Arguments> rows() {
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
            "moduleApiDoesNotExposeInternalTypes: shared.tenant の TenantContext",
            PackageByFeatureOnionArchitectureTest.moduleApiDoesNotExposeInternalTypesRule(
                VIOLATING),
            "ordering.OrderPlacedReadingTenantContext"),
        row(
            "domainModelDoesNotDependOnFrameworks",
            PackageByFeatureOnionArchitectureTest.domainModelDoesNotDependOnFrameworks,
            "ordering.domain.model.OrderFactory"),
        row(
            "valueObjectsDoNotThrowBusinessRuleViolation",
            PackageByFeatureOnionArchitectureTest.valueObjectsDoNotThrowBusinessRuleViolation,
            "ordering.domain.model.Discount"),
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
            "externalSystemCommandHandlersAreDependedOnlyByListeners: Controller",
            ClassRoleArchTest.externalSystemCommandHandlersAreDependedOnlyByListeners,
            "ordering.presentation.web.ChargeOrderController"),
        row(
            "externalSystemCommandHandlersAreDependedOnlyByListeners: application の外の Listener",
            ClassRoleArchTest.externalSystemCommandHandlersAreDependedOnlyByListeners,
            "ordering.presentation.web.ChargeOrderListener"),
        row(
            "externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients: Domain Service",
            ClassRoleArchTest.externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients,
            "ordering.domain.service.PaymentChargePolicy"),
        row(
            "externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients: domain.model",
            ClassRoleArchTest.externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients,
            "ordering.domain.model.PaymentCharger"),
        row(
            "externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients: Client ではない設定クラス",
            ClassRoleArchTest.externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients,
            "ordering.infrastructure.client.PaymentChargerConfig"),
        row(
            "externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients: 補助のクラス",
            ClassRoleArchTest.externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients,
            "ordering.application.OrderPaymentCharger"),
        row(
            "eventPublisherIsUsedOnlyByCommandHandlers: Controller",
            ClassRoleArchTest.eventPublisherIsUsedOnlyByCommandHandlers,
            "ordering.presentation.web.OrderEventController"),
        row(
            "eventPublisherIsUsedOnlyByCommandHandlers: ApplicationEventMulticaster",
            ClassRoleArchTest.eventPublisherIsUsedOnlyByCommandHandlers,
            "ordering.presentation.web.OrderEventMulticasterController"),
        row(
            "eventPublisherIsUsedOnlyByCommandHandlers: ApplicationContext",
            ClassRoleArchTest.eventPublisherIsUsedOnlyByCommandHandlers,
            "ordering.application.OrderContextPublisher"),
        row(
            "eventPublisherIsUsedOnlyByCommandHandlers: shared",
            ClassRoleArchTest.eventPublisherIsUsedOnlyByCommandHandlers,
            "shared.events.OrderEventForwarder"),
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
            "commandHandlersAndListenersDoNotImplementInterfaces: CommandHandler",
            ClassRoleArchTest.commandHandlersAndListenersDoNotImplementInterfaces,
            "ordering.application.ShelveOrderCommandHandler"),
        row(
            "commandHandlersAndListenersDoNotImplementInterfaces: Listener",
            ClassRoleArchTest.commandHandlersAndListenersDoNotImplementInterfaces,
            "ordering.application.ChargeTriggerListener"),
        row(
            "listenersAreNotCalled",
            ClassRoleArchTest.listenersAreNotCalled,
            "ordering.presentation.web.OrderPaidController"),
        row(
            "moduleListenerEventsAreTenantScoped",
            ClassRoleArchTest.moduleListenerEventsAreTenantScopedRule(VIOLATING),
            "inventory.application.OrderPlacedWithoutTenantListener.on("),
        row(
            "tenantIsBoundOnlyByTenantSelectionFilterAndListenerAspect",
            ClassRoleArchTest.tenantIsBoundOnlyByTenantSelectionFilterAndListenerAspectRule(
                VIOLATING),
            "ordering.application.TenantSwitchingCommandHandler"),
        row(
            "tenantIsBoundOnlyByTenantSelectionFilterAndListenerAspect: 別のモジュールの filter のメソッド参照",
            ClassRoleArchTest.tenantIsBoundOnlyByTenantSelectionFilterAndListenerAspectRule(
                VIOLATING),
            "ordering.presentation.web.HeaderTenantFilter"),
        row(
            "unboundTenantIsReadOnlyByTransactionManager",
            ClassRoleArchTest.unboundTenantIsReadOnlyByTransactionManagerRule(VIOLATING),
            "ordering.application.TenantDefaultingCommandHandler"),
        row(
            "roleAndTenantAreSetOnlyByTransactionManager",
            ClassRoleArchTest.roleAndTenantAreSetOnlyByTransactionManagerRule(VIOLATING),
            "ordering.infrastructure.persistence.TenantSwitchingOrderAccess"),
        row(
            "eventListenersAreApplicationModuleListeners: @EventListener",
            ClassRoleArchTest.eventListenersAreApplicationModuleListeners,
            "inventory.application.StockEventReceiver.onPlaced("),
        row(
            "eventListenersAreApplicationModuleListeners: @TransactionalEventListener",
            ClassRoleArchTest.eventListenersAreApplicationModuleListeners,
            "inventory.application.StockEventReceiver.afterPlaced("),
        row(
            "applicationListenerIsNotImplemented",
            ClassRoleArchTest.applicationListenerIsNotImplemented,
            "inventory.application.StockLevelWatcher"),
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

  private ClassRoleFixtureRows() {}
}
