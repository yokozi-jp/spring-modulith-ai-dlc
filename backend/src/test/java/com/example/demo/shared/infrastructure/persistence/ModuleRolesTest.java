package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import com.example.demo.jooq.DefaultCatalog;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.modulith.core.ApplicationModules;

/** トランザクションの名前から、切り替えるモジュールのロールを引く規則を確かめる。 */
class ModuleRolesTest {

  @ParameterizedTest(name = "{0} は {1} のロールで動く")
  @CsvSource({
    "com.example.demo.ordering.application.ConfirmOrderCommandHandler.handle, ordering_module",
    "com.example.demo.payment.application.OrderConfirmedListener.on, payment_module",
    "com.example.demo.product.application.ProductQueryService.search, product_module",
    "com.example.demo.ordering.infrastructure.persistence.JooqOrderRepositoryTest, ordering_module",
    "com.example.demo.ordering.Transactions.run, ordering_module"
  })
  @DisplayName("モジュールのパッケージの名前は、そのモジュールのロールを引く")
  void resolvesModuleRole(final String transactionName, final String role) {
    assertThat(ModuleRoles.roleFor(transactionName)).contains(role);
  }

  @ParameterizedTest(name = "{0} はアプリロールのまま動く")
  @CsvSource({
    "com.example.demo.shared.infrastructure.persistence.EventPublicationResubmitter.run",
    "com.example.demo.error.presentation.web.ApiExceptionHandler.handle",
    "com.example.demo.DemoApplication.main",
    "com.example.demo.orderingextra.Other.run",
    "org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository.markCompleted",
    "ordering.application.X.y"
  })
  @DisplayName("モジュールでない名前は、ロールを引かない")
  void doesNotResolveNonModuleNames(final String transactionName) {
    assertThat(ModuleRoles.roleFor(transactionName)).isEmpty();
  }

  @ParameterizedTest(name = "{0} はテナントが要る: {1}")
  @CsvSource({
    "com.example.demo.product.application.ProductQueryService.search, true",
    "com.example.demo.ordering.application.ConfirmOrderCommandHandler.handle, true",
    "com.example.demo.payment.application.OrderConfirmedListener.on, true",
    "com.example.demo.tenancy.application.SignInUserCommandHandler.handle, false",
    "com.example.demo.shared.infrastructure.persistence.EventPublicationResubmitter.run, false",
    "org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository.markCompleted, false"
  })
  @DisplayName("業務の表を持つモジュールの名前だけが、テナントを要する")
  void requiresTenantOnlyForBusinessModules(final String transactionName, final boolean required) {
    assertThat(ModuleRoles.requiresTenant(transactionName)).isEqualTo(required);
    assertThat(ModuleRoles.requiresTenant(null)).isFalse();
  }

  @Test
  @DisplayName("名前のないトランザクションは、ロールを引かない")
  void doesNotResolveMissingName() {
    assertThat(ModuleRoles.roleFor(null)).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("モジュールの一覧は、modulith を除く jOOQ のスキーマと同じで、どれも Spring Modulith のモジュールである")
  void modulesMatchGeneratedSchemas() {
    final Set<String> schemas =
        DefaultCatalog.DEFAULT_CATALOG.getSchemas().stream()
            .map(Schema::getName)
            .filter(name -> !"modulith".equals(name))
            .collect(Collectors.toSet());
    final Set<String> applicationModules =
        ApplicationModules.of(DemoApplication.class).stream()
            .map(module -> module.getIdentifier().toString())
            .collect(Collectors.toSet());

    assertThat(ModuleRoles.MODULES).isEqualTo(schemas);
    assertThat(applicationModules).containsAll(ModuleRoles.MODULES);
  }

  @Test
  @DisplayName("モジュールの一覧は、ロールを作る手順の一覧と同じである")
  void modulesMatchRoleCreationScript() throws java.io.IOException {
    final String script =
        java.nio.file.Files.readString(
            java.nio.file.Path.of("..", "docker", "initdb", "03-create-module-roles.sh"));

    final java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("(?m)^modules=\"([^\"]+)\"$").matcher(script);

    assertThat(matcher.find()).as("03-create-module-roles.sh の modules=").isTrue();
    assertThat(Set.of(matcher.group(1).split(" "))).isEqualTo(ModuleRoles.MODULES);
  }
}
