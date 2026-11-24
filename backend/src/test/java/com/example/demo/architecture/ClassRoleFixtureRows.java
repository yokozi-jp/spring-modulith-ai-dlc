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

  /** 例外を Problem Details へ変える違反フィクスチャのクラス名。 */
  private static final String ERROR_ADVICE = "ordering.presentation.web.OrderErrorAdvice";

  /** 違反フィクスチャの {@code OrderErrorAdvice} の、例外を変えるメソッド。 */
  private static final String ERROR_ADVICE_HANDLE =
      ERROR_ADVICE + ".handle(java.lang.IllegalStateException)>";

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
            "httpTypesAreNotUsedByInnerLayers: shared",
            PackageByFeatureOnionArchitectureTest.httpTypesAreNotUsedByInnerLayersRule(VIOLATING),
            "shared.persistence.ChildRowWriter"),
        row(
            "httpTypesAreNotUsedByInnerLayers: application",
            PackageByFeatureOnionArchitectureTest.httpTypesAreNotUsedByInnerLayersRule(VIOLATING),
            "ordering.application.HttpStatusCommandHandler"),
        row(
            "httpTypesAreNotUsedByInnerLayers: infrastructure.persistence",
            PackageByFeatureOnionArchitectureTest.httpTypesAreNotUsedByInnerLayersRule(VIOLATING),
            "ordering.infrastructure.persistence.HttpStatusOrderReader"),
        httpErrorRow(
            "RestControllerAdvice",
            ERROR_ADVICE
                + "> is annotated with <org.springframework.web.bind.annotation.RestControllerAdvice>"),
        httpErrorRow(
            "ControllerAdvice",
            "ordering.presentation.web.OrderStatusAdvice> is annotated with "
                + "<org.springframework.web.bind.annotation.ControllerAdvice>"),
        httpErrorRow(
            "ExceptionHandler",
            ERROR_ADVICE_HANDLE
                + " is annotated with <org.springframework.web.bind.annotation.ExceptionHandler>"),
        httpErrorRow(
            "ResponseStatus",
            ERROR_ADVICE_HANDLE
                + " is annotated with <org.springframework.web.bind.annotation.ResponseStatus>"),
        httpErrorRow(
            "ProblemDetail",
            ERROR_ADVICE_HANDLE + " has return type <org.springframework.http.ProblemDetail>"),
        httpErrorRow(
            "ErrorResponseException",
            "ordering.presentation.web.OrderRejectedException> extends class "
                + "<org.springframework.web.ErrorResponseException>"),
        httpErrorRow(
            "ResponseStatusException",
            "ordering.application.HttpStatusCommandHandler.handle()> calls constructor "
                + "<org.springframework.web.server.ResponseStatusException.<init>"),
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
            "moduleApiDoesNotExposeInternalTypes: shared.tenancy の TenantContext",
            PackageByFeatureOnionArchitectureTest.moduleApiDoesNotExposeInternalTypesRule(
                VIOLATING),
            "ordering.OrderPlacedReadingTenantContext"),
        row(
            "domainModelDependsOnlyOnAllowedTypes: Spring のアノテーション",
            PackageByFeatureOnionArchitectureTest.domainModelDependsOnlyOnAllowedTypesRule(
                VIOLATING),
            "ordering.domain.model.OrderFactory"),
        row(
            "domainModelDependsOnlyOnAllowedTypes: 外部ライブラリの型を戻り値にした interface",
            PackageByFeatureOnionArchitectureTest.domainModelDependsOnlyOnAllowedTypesRule(
                VIOLATING),
            "ordering.domain.model.OrderRetryPolicy"),
        row(
            "domainModelDependsOnlyOnAllowedTypes: SLF4J の Logger",
            PackageByFeatureOnionArchitectureTest.domainModelDependsOnlyOnAllowedTypesRule(
                VIOLATING),
            "ordering.domain.model.LoggingOrder"),
        row(
            "domainModelDependsOnlyOnAllowedTypes: java.net.http の HttpClient",
            PackageByFeatureOnionArchitectureTest.domainModelDependsOnlyOnAllowedTypesRule(
                VIOLATING),
            "ordering.domain.model.RemoteOrderLookup"),
        row(
            "domainModelDependsOnlyOnAllowedTypes: shared.tenancy.TenantContext",
            PackageByFeatureOnionArchitectureTest.domainModelDependsOnlyOnAllowedTypesRule(
                VIOLATING),
            "ordering.domain.model.TenantReadingOrder"),
        row(
            "applicationDependsOnlyOnAllowedTypes",
            PackageByFeatureOnionArchitectureTest.applicationDependsOnlyOnAllowedTypesRule(
                VIOLATING),
            "ordering.application.OrderRetryRunner"),
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
            "commandsAndResultsAreApplicationRecords: sealed でない interface の Result",
            ClassRoleArchTest.commandsAndResultsAreApplicationRecordsRule(VIOLATING),
            "ordering.application.RefundOrderResult"),
        row(
            "commandsAndResultsAreApplicationRecords: 許可された型に class を持つ Result",
            ClassRoleArchTest.commandsAndResultsAreApplicationRecordsRule(VIOLATING),
            "ordering.application.VoidOrderResult"),
        row(
            "commandsAndResultsAreApplicationRecords: shared.concurrency",
            ClassRoleArchTest.commandsAndResultsAreApplicationRecordsRule(VIOLATING),
            "shared.concurrency.ForceUnlockCommand"),
        row(
            "resultComponentsAreNotNullable",
            ClassRoleArchTest.resultComponentsAreNotNullableRule(VIOLATING),
            "ordering.application.HoldOrderResult"),
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

  /** {@code httpErrorsAreMappedOnlyInErrorPresentationWeb} が、型か注釈への依存を 1 つ検出することを確かめる行を作る。 */
  private static Arguments httpErrorRow(final String type, final String violation) {
    return row(
        "httpErrorsAreMappedOnlyInErrorPresentationWeb: " + type,
        PackageByFeatureOnionArchitectureTest.httpErrorsAreMappedOnlyInErrorPresentationWebRule(
            VIOLATING),
        violation);
  }

  private ClassRoleFixtureRows() {}
}
