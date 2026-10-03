package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.stream.Stream;
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
            "order.application.OrderQueryService"));
  }

  private static Arguments row(
      final String ruleName, final ArchRule rule, final String violatingClass) {
    return Arguments.of(ruleName, rule, VIOLATING_PREFIX + violatingClass);
  }

  /** 両方の検査クラスの全規則を、指定した基底パッケージで組み立てて返す。 */
  private static List<ArchRule> rulesFor(final String basePackage) {
    return List.of(
        PackageByFeatureOnionArchitectureTest.dependenciesPointInwardRule(basePackage),
        PackageByFeatureOnionArchitectureTest.infrastructureDependsOnlyOnDomainModelRule(
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
        ClassRoleArchTest.queryServicesImplementModuleQueries);
  }
}
