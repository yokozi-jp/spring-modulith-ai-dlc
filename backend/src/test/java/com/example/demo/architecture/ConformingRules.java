package com.example.demo.architecture;

import static java.util.Map.entry;

import com.tngtech.archunit.lang.ArchRule;
import java.util.Map;
import java.util.Set;

/** {@link ArchitectureRuleFixtureTest} が規約どおりのフィクスチャで確かめる、{@code @ArchTest} の規則の一覧。 */
final class ConformingRules {

  /** ロールとスキーマを持つ機能モジュールの、フィクスチャでの名前。 */
  /* package */ static final Set<String> FIXTURE_MODULES = Set.of("ordering", "inventory");

  /** jOOQ の生成クラスの基底パッケージ。フィクスチャは本物の生成クラスを使う。 */
  /* package */ static final String JOOQ_PACKAGE = "com.example.demo.jooq";

  private ConformingRules() {}

  /** 検査クラスの全規則を、指定した基底パッケージで組み立て、{@code @ArchTest} のフィールドの名前をキーにして返す。 */
  /* package */ static Map<String, ArchRule> rulesFor(final String basePackage) {
    return Map.ofEntries(
        entry(
            "dependenciesPointInward",
            PackageByFeatureOnionArchitectureTest.dependenciesPointInwardRule(basePackage)),
        entry(
            "infrastructureDependsOnlyOnDomainModel",
            PackageByFeatureOnionArchitectureTest.infrastructureDependsOnlyOnDomainModelRule(
                basePackage)),
        entry(
            "sharedModuleIsUsedOnlyByPersistenceAdapters",
            PackageByFeatureOnionArchitectureTest.sharedModuleIsUsedOnlyByPersistenceAdaptersRule(
                basePackage)),
        entry(
            "httpTypesAreNotUsedByInnerLayers",
            PackageByFeatureOnionArchitectureTest.httpTypesAreNotUsedByInnerLayersRule(
                basePackage)),
        entry(
            "httpErrorsAreMappedOnlyInErrorPresentationWeb",
            PackageByFeatureOnionArchitectureTest.httpErrorsAreMappedOnlyInErrorPresentationWebRule(
                basePackage)),
        entry(
            "failureReasonsAreModuleEnums",
            PackageByFeatureOnionArchitectureTest.failureReasonsAreModuleEnumsRule(basePackage)),
        entry(
            "moduleApiDoesNotExposeInternalTypes",
            PackageByFeatureOnionArchitectureTest.moduleApiDoesNotExposeInternalTypesRule(
                basePackage)),
        entry(
            "databaseTechnologyApisAreOnlyUsedByPersistenceAdapters",
            PackageByFeatureOnionArchitectureTest
                .databaseTechnologyApisAreOnlyUsedByPersistenceAdaptersRule(basePackage)),
        entry(
            "noSuchElementExceptionIsNotThrown",
            GeneralCodingRulesArchTest.noSuchElementExceptionIsNotThrown),
        entry(
            "featureCodeUsesOnlySlf4jFacade",
            GeneralCodingRulesArchTest.featureCodeUsesOnlySlf4jFacade),
        entry("autowiredMethodsAreNotUsed", GeneralCodingRulesArchTest.autowiredMethodsAreNotUsed),
        entry(
            "legacyDateTimeTypesAreNotUsed",
            DateTimeConventionsArchTest.legacyDateTimeTypesAreNotUsed),
        entry(
            "domainModelDependsOnlyOnAllowedTypes",
            PackageByFeatureOnionArchitectureTest.domainModelDependsOnlyOnAllowedTypesRule(
                basePackage)),
        entry(
            "applicationDependsOnlyOnAllowedTypes",
            PackageByFeatureOnionArchitectureTest.applicationDependsOnlyOnAllowedTypesRule(
                basePackage)),
        entry(
            "valueObjectsDoNotThrowBusinessRuleViolation",
            PackageByFeatureOnionArchitectureTest.valueObjectsDoNotThrowBusinessRuleViolation),
        entry(
            "domainServicesDependOnlyOnDomainAndJava",
            PackageByFeatureOnionArchitectureTest.domainServicesDependOnlyOnDomainAndJavaRule(
                basePackage)),
        entry(
            "domainServicesAreAnnotatedWithService",
            PackageByFeatureOnionArchitectureTest.domainServicesAreAnnotatedWithService),
        entry(
            "servicesResideInApplicationOrDomainService",
            PackageByFeatureOnionArchitectureTest.servicesResideInApplicationOrDomainService),
        entry(
            "controllersResideInPresentationWeb",
            PackageByFeatureOnionArchitectureTest.controllersResideInPresentationWeb),
        entry(
            "repositoriesResideInPersistenceAdapters",
            PackageByFeatureOnionArchitectureTest.repositoriesResideInPersistenceAdapters),
        entry(
            "presentationDoesNotDependOnDomain",
            PackageByFeatureOnionArchitectureTest.presentationDoesNotDependOnDomain),
        entry(
            "domainInterfacesAreImplementedInInfrastructure",
            PackageByFeatureOnionArchitectureTest
                .domainInterfacesAreImplementedInInfrastructureRule(basePackage)),
        entry(
            "repositoryImplementationsAreJooqRepositories",
            PackageByFeatureOnionArchitectureTest.repositoryImplementationsAreJooqRepositories),
        entry(
            "externalSystemImplementationsAreClients",
            PackageByFeatureOnionArchitectureTest.externalSystemImplementationsAreClients),
        entry(
            "transactionalIsNotDeclaredAtClassLevel",
            PackageByFeatureOnionArchitectureTest.transactionalIsNotDeclaredAtClassLevel),
        entry(
            "transactionalMethodsArePublicApplicationMethods",
            PackageByFeatureOnionArchitectureTest.transactionalMethodsArePublicApplicationMethods),
        entry(
            "moduleRootTypesAreRecordsEnumsOrQueries",
            ClassRoleArchTest.moduleRootTypesAreRecordsEnumsOrQueriesRule(basePackage)),
        entry(
            "applicationServicesHaveRoleNames", ClassRoleArchTest.applicationServicesHaveRoleNames),
        entry(
            "commandHandlersExposeOnlyTransactionalHandle",
            ClassRoleArchTest.commandHandlersExposeOnlyTransactionalHandle),
        entry(
            "commandsAndResultsAreApplicationRecords",
            ClassRoleArchTest.commandsAndResultsAreApplicationRecordsRule(basePackage)),
        entry(
            "resultComponentsAreNotNullable",
            ClassRoleArchTest.resultComponentsAreNotNullableRule(basePackage)),
        entry(
            "commandHandlersDoNotDependOnOtherCommandHandlers",
            ClassRoleArchTest.commandHandlersDoNotDependOnOtherCommandHandlers),
        entry(
            "commandHandlersAndListenersDoNotUseInheritance",
            ClassRoleArchTest.commandHandlersAndListenersDoNotUseInheritance),
        entry(
            "externalSystemCommandHandlersAreDependedOnlyByListeners",
            ClassRoleArchTest.externalSystemCommandHandlersAreDependedOnlyByListeners),
        entry(
            "externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients",
            ClassRoleArchTest.externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients),
        entry(
            "eventPublisherIsUsedOnlyByCommandHandlers",
            ClassRoleArchTest.eventPublisherIsUsedOnlyByCommandHandlers),
        entry(
            "commandHandlersAndListenersDoNotImplementInterfaces",
            ClassRoleArchTest.commandHandlersAndListenersDoNotImplementInterfaces),
        entry("listenersAreNotCalled", ClassRoleArchTest.listenersAreNotCalled),
        entry(
            "pointcutsNarrowTargetsWithWithin",
            ProxyRulesArchTest.pointcutsNarrowTargetsWithWithin),
        entry("aspectsDeclareOrder", ProxyRulesArchTest.aspectsDeclareOrder),
        entry(
            "transactionManagementIsNotRedeclared",
            ProxyRulesArchTest.transactionManagementIsNotRedeclared),
        entry(
            "proxiedMethodsAreNotCalledFromSameClass",
            ProxyRulesArchTest.proxiedMethodsAreNotCalledFromSameClass),
        entry(
            "proxiedMethodsAreNotPrivateFinalOrStatic",
            ProxyRulesArchTest.proxiedMethodsAreNotPrivateFinalOrStatic),
        entry("publicBeanMethodsAreNotFinal", ProxyRulesArchTest.publicBeanMethodsAreNotFinal),
        entry("asyncMethodsReturnVoidOrFuture", ProxyRulesArchTest.asyncMethodsReturnVoidOrFuture),
        entry(
            "scheduledMethodsAreNoArgVoidAndNotPrivateOrFinal",
            ProxyRulesArchTest.scheduledMethodsAreNoArgVoidAndNotPrivateOrFinal),
        entry(
            "moduleListenersAreApplicationListeners",
            ClassRoleArchTest.moduleListenersAreApplicationListeners),
        entry(
            "moduleListenerEventsAreTenantScoped",
            ClassRoleArchTest.moduleListenerEventsAreTenantScopedRule(basePackage)),
        entry(
            "tenantIsBoundOnlyByAllowedCallers",
            ClassRoleArchTest.tenantIsBoundOnlyByAllowedCallersRule(basePackage)),
        entry(
            "unboundTenantIsReadOnlyByTransactionManager",
            ClassRoleArchTest.unboundTenantIsReadOnlyByTransactionManagerRule(basePackage)),
        entry(
            "roleAndTenantAreSetOnlyByTransactionManager",
            ClassRoleArchTest.roleAndTenantAreSetOnlyByTransactionManagerRule(basePackage)),
        entry(
            "eventListenersAreApplicationModuleListeners",
            ClassRoleArchTest.eventListenersAreApplicationModuleListeners),
        entry(
            "applicationListenerIsNotImplemented",
            ClassRoleArchTest.applicationListenerIsNotImplemented),
        entry(
            "listenersExposeOnlyOnAndCallOneCommandHandler",
            ClassRoleArchTest.listenersExposeOnlyOnAndCallOneCommandHandler),
        entry(
            "requestsAndResponsesArePresentationWebRecords",
            ClassRoleArchTest.requestsAndResponsesArePresentationWebRecordsRule(basePackage)),
        entry(
            "queryServicesImplementModuleQueries",
            ClassRoleArchTest.queryServicesImplementModuleQueries),
        entry(
            "queryResultStatusIsNotReadByOtherModules",
            ClassRoleArchTest.queryResultStatusIsNotReadByOtherModulesRule(basePackage)),
        entry("mappingLibrariesAreNotUsed", ClassRoleArchTest.mappingLibrariesAreNotUsed),
        entry("jooqReflectionMappingIsNotUsed", ClassRoleArchTest.jooqReflectionMappingIsNotUsed),
        entry(
            "tableWritesGoThroughTableWriter",
            TableWriterArchTest.tableWritesGoThroughTableWriterRule(basePackage)),
        entry(
            "repositoryUpdateCallsVersionedEntryPoint",
            TableWriterArchTest.repositoryUpdateCallsVersionedEntryPointRule(basePackage)),
        entry(
            "repositoryWritesTakeVersionedAggregates",
            TableWriterArchTest.repositoryWritesTakeVersionedAggregatesRule()),
        entry(
            "commandHandlersEnsureScreenLockNo",
            TableWriterArchTest.commandHandlersEnsureScreenLockNoRule(basePackage)),
        entry(
            "commandsBuiltByPresentationForWritesAreVersioned",
            TableWriterArchTest.commandsBuiltByPresentationForWritesAreVersionedRule(basePackage)),
        entry(
            "onlyCommandHandlersUpdateOrDeleteAggregates",
            TableWriterArchTest.onlyCommandHandlersUpdateOrDeleteAggregatesRule()),
        entry(
            "expectedLockNoIsCreatedOnlyByRequests",
            TableWriterArchTest.expectedLockNoIsCreatedOnlyByRequestsRule(basePackage)),
        entry(
            "unsafeHandlersTakeIdempotencyKey",
            IdempotencyArchTest.unsafeHandlersTakeIdempotencyKeyRule(basePackage)),
        entry(
            "idempotentCommandHandlersExecuteThroughIdempotency",
            IdempotencyArchTest.idempotentCommandHandlersExecuteThroughIdempotencyRule(
                basePackage)),
        entry(
            "apiControllersDeclareVersion",
            ApiVersionArchTest.apiControllersDeclareVersionRule(basePackage)),
        entry(
            "numericIdsDoNotLeaveModules",
            PublicIdArchTest.numericIdsDoNotLeaveModulesRule(basePackage)),
        entry(
            "persistenceAdaptersUseOnlyOwnGeneratedSchema",
            DatabaseConventionsArchTest.ownGeneratedSchemaRule(
                basePackage, JOOQ_PACKAGE, FIXTURE_MODULES)),
        entry(
            "moduleCodeDoesNotUseJdbc",
            DatabaseConventionsArchTest.moduleCodeDoesNotUseJdbcRule(basePackage, FIXTURE_MODULES)),
        entry(
            "moduleCodeDoesNotChangeConnectionSettings",
            DatabaseConventionsArchTest.moduleCodeDoesNotChangeConnectionSettingsRule(
                basePackage, FIXTURE_MODULES)));
  }
}
