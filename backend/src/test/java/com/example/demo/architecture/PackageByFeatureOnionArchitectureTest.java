package com.example.demo.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.Architectures;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * Package by feature で分割した各機能モジュールの内部に、オニオンアーキテクチャとクラスの置き場所を強制する。
 *
 * <p>基底パッケージを含む規則は {@code xxxRule(basePackage)} のファクトリで組み立て、{@link ArchitectureRuleFixtureTest}
 * がフィクスチャのパッケージで同じ規則を再利用する。
 */
// ArchUnit の JUnit 5 エンジンが @ArchTest フィールドを読むため、規則ファクトリだけを持つクラスでもユーティリティクラスにしない。
@SuppressWarnings({
  "PMD.TestClassWithoutTestCases",
  "PMD.TooManyStaticImports",
  "PMD.UseUtilityClass"
})
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class PackageByFeatureOnionArchitectureTest {

  /** プロダクションコードの基底パッケージ。 */
  private static final String BASE_PACKAGE = DemoApplication.class.getPackageName();

  /** 機能ルートを公開契約として扱い、Presentation と各 Infrastructure Adapter から Domain へ依存を向ける。 */
  @ArchTest
  /* package */ static final ArchRule dependenciesPointInward =
      dependenciesPointInwardRule(BASE_PACKAGE);

  /** 機能ルートの公開契約は、標準型、JSpecify、同じルートパッケージの型だけに依存させる。 */
  @ArchTest
  /* package */ static final ArchRule moduleApiDoesNotExposeInternalTypes =
      moduleApiDoesNotExposeInternalTypesRule(BASE_PACKAGE);

  /** DB 技術 API を各機能の Persistence Adapter に閉じ込める。 */
  @ArchTest
  /* package */ static final ArchRule databaseTechnologyApisAreOnlyUsedByPersistenceAdapters =
      noClasses()
          .that()
          .resideOutsideOfPackage("..infrastructure.persistence..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.jooq..",
              "com.example.demo.jooq..",
              "java.sql..",
              "javax.sql..",
              "org.springframework.jdbc..")
          .because(
              "jOOQ、JDBC、生成型は Persistence Adapter 内で Domain 型へ変換し、"
                  + "公開契約、Application、Domain、Presentation へ漏らさない。");

  /** Domain Model を Spring、jOOQ、JPA、Jackson から独立させる。 */
  @ArchTest
  /* package */ static final ArchRule domainModelDoesNotDependOnFrameworks =
      noClasses()
          .that()
          .resideInAPackage("..domain.model..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "org.jooq..",
              "com.example.demo.jooq..",
              "jakarta.persistence..",
              "com.fasterxml.jackson..",
              "tools.jackson..")
          .allowEmptyShould(true)
          .because("domain.model からフレームワークの型とアノテーションを取り除き、業務モデルと業務規則だけを置く。");

  /** Domain Service は標準型、JSpecify、Lombok、Domain と {@code @Service} だけに依存させる。 */
  @ArchTest
  /* package */ static final ArchRule domainServicesDependOnlyOnDomainAndJava =
      domainServicesDependOnlyOnDomainAndJavaRule(BASE_PACKAGE);

  /** Domain Service のトップレベルクラスには {@code @Service} を付ける。 */
  @ArchTest
  /* package */ static final ArchRule domainServicesAreAnnotatedWithService =
      classes()
          .that()
          .resideInAPackage("..domain.service..")
          .and()
          .areTopLevelClasses()
          .and()
          .doNotHaveSimpleName("package-info")
          .should()
          .beAnnotatedWith(Service.class)
          .allowEmptyShould(true)
          .because("domain.service のクラスに @Service を付け、コンストラクタ注入で Bean として使う。");

  /** Spring の Service を Application と Domain Service に置く。 */
  @ArchTest
  /* package */ static final ArchRule servicesResideInApplicationOrDomainService =
      classes()
          .that()
          .areAnnotatedWith(Service.class)
          .should()
          .resideInAnyPackage("..application..", "..domain.service..")
          .allowEmptyShould(true)
          .because(
              "@Service のクラスは application（CommandHandler、QueryService、Listener）か "
                  + "domain.service へ移す。Adapter の Bean には @Repository か @Component を付ける。");

  /** Spring MVC の Controller を {@code presentation.web} に置き、名前を {@code Controller} で終える。 */
  @ArchTest
  /* package */ static final ArchRule controllersResideInPresentationWeb =
      classes()
          .that()
          .areAnnotatedWith(Controller.class)
          .or()
          .areAnnotatedWith(RestController.class)
          .should()
          .resideInAPackage("..presentation.web..")
          .andShould()
          .haveSimpleNameEndingWith("Controller")
          .allowEmptyShould(true)
          .because("Controller は <モジュール>.presentation.web へ移し、<Aggregate>Controller と命名する。");

  /** Spring の Repository を Persistence Adapter に置く。 */
  @ArchTest
  /* package */ static final ArchRule repositoriesResideInPersistenceAdapters =
      classes()
          .that()
          .areAnnotatedWith(Repository.class)
          .should()
          .resideInAPackage("..infrastructure.persistence..")
          .allowEmptyShould(true)
          .because("永続化実装は各機能の Persistence Adapter に閉じ込める。");

  /** Presentation から Domain への依存を禁止する。 */
  @ArchTest
  /* package */ static final ArchRule presentationDoesNotDependOnDomain =
      noClasses()
          .that()
          .resideInAPackage("..presentation..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..domain..")
          .allowEmptyShould(true)
          .because(
              "Presentation は Command、Result、モジュールルートの型だけを使い、"
                  + "Domain の型は Application の CommandHandler か QueryService の内側で変換する。");

  /** Domain の interface を Domain の外で実装するクラスは Infrastructure に置く。 */
  @ArchTest
  /* package */ static final ArchRule domainInterfacesAreImplementedInInfrastructure =
      domainInterfacesAreImplementedInInfrastructureRule(BASE_PACKAGE);

  /** Domain の Repository の実装は {@code Jooq<Aggregate>Repository} として Persistence Adapter に置く。 */
  @ArchTest
  /* package */ static final ArchRule repositoryImplementationsAreJooqRepositories =
      classes()
          .that()
          .implement(simpleNameEndingWith("Repository").and(resideInAPackage("..domain.model..")))
          .should()
          .haveSimpleNameStartingWith("Jooq")
          .andShould()
          .haveSimpleNameEndingWith("Repository")
          .andShould()
          .resideInAPackage("..infrastructure.persistence..")
          .allowEmptyShould(true)
          .because(
              "Repository の実装は infrastructure.persistence へ移し、Jooq<Aggregate>Repository と命名する。");

  /** Domain の外部システム interface の実装は {@code <ExternalSystem>Client} として Client Adapter に置く。 */
  @ArchTest
  /* package */ static final ArchRule externalSystemImplementationsAreClients =
      classes()
          .that()
          .resideOutsideOfPackage("..domain..")
          .and()
          .implement(
              resideInAPackage("..domain.model..").and(not(simpleNameEndingWith("Repository"))))
          .should()
          .haveSimpleNameEndingWith("Client")
          .andShould()
          .resideInAPackage("..infrastructure.client..")
          .allowEmptyShould(true)
          .because("外部システム interface の実装は infrastructure.client へ移し、<ExternalSystem>Client と命名する。");

  /** クラス単位の {@code @Transactional} を、合成アノテーション経由も含めて禁止する。 */
  @ArchTest
  /* package */ static final ArchRule transactionalIsNotDeclaredAtClassLevel =
      noClasses()
          .should()
          .beMetaAnnotatedWith(Transactional.class)
          .because("クラスの @Transactional を外し、Application の public メソッドへ付け直す。");

  /** メソッド単位のトランザクション境界を、合成アノテーション経由も含めて Application の public メソッドに置く。 */
  @ArchTest
  /* package */ static final ArchRule transactionalMethodsArePublicApplicationMethods =
      methods()
          .that()
          .areMetaAnnotatedWith(Transactional.class)
          .should()
          .beDeclaredInClassesThat()
          .resideInAPackage("..application..")
          .andShould()
          .bePublic()
          .allowEmptyShould(true)
          .because(
              "@Transactional と @ApplicationModuleListener は Application の public メソッドへ移す。"
                  + "トランザクション境界をプロキシ方式に左右されないユースケースの入口に置く。");

  /** 基底パッケージ配下の各機能モジュールに、外側から内側へ向かう依存を強制する規則を組み立てる。 */
  /* package */ static ArchRule dependenciesPointInwardRule(final String basePackage) {
    return Architectures.onionArchitecture()
        .domainModels(basePackage + ".*.domain.model..")
        .domainServices(basePackage + ".*.domain.service..")
        .applicationServices(basePackage + ".*", basePackage + ".*.application..")
        .adapter("presentation", basePackage + ".*.presentation..")
        .adapter("persistence", basePackage + ".*.infrastructure.persistence..")
        .adapter("external-client", basePackage + ".*.infrastructure.client..")
        .ensureAllClassesAreContainedInArchitectureIgnoring(basePackage)
        .withOptionalLayers(true)
        .because(
            "機能モジュール内の依存は外側から内側へ向け、"
                + "Presentation、Persistence、外部 Client を相互に依存させない。"
                + "どの層にも属さないパッケージのクラスは、役割に合う層のパッケージへ移す。");
  }

  /** 機能ルートの型が標準型、JSpecify、同じルートパッケージの型だけに依存することを強制する規則を組み立てる。 */
  /* package */ static ArchRule moduleApiDoesNotExposeInternalTypesRule(final String basePackage) {
    return classes()
        .that()
        .resideInAPackage(basePackage + ".*")
        .should(dependOnlyOnStandardTypesOrOwnPackage())
        .allowEmptyShould(true)
        .because(
            "モジュールルートの型の項目は String、Instant、BigDecimal などの標準型か、"
                + "同じルートパッケージの record と enum に置き換える。"
                + "内部パッケージと他モジュールの型を公開契約に露出させない。");
  }

  /** Domain Service の依存先を標準型、JSpecify、Lombok、Domain と {@code @Service} に限る規則を組み立てる。 */
  /* package */ static ArchRule domainServicesDependOnlyOnDomainAndJavaRule(
      final String basePackage) {
    return classes()
        .that()
        .resideInAPackage("..domain.service..")
        .should()
        .onlyDependOnClassesThat(
            resideInAnyPackage("java..", "org.jspecify..", "lombok..", basePackage + ".*.domain..")
                .or(type(Service.class)))
        .allowEmptyShould(true)
        .because(
            "Domain Service は Domain の型と標準型だけで業務規則を書く。"
                + "イベント発行、外部呼び出し、ログ出力は Application の CommandHandler へ移す。");
  }

  /** Domain の外で Domain Model の interface を実装するクラスを Infrastructure に置く規則を組み立てる。 */
  /* package */ static ArchRule domainInterfacesAreImplementedInInfrastructureRule(
      final String basePackage) {
    return classes()
        .that()
        .resideOutsideOfPackage("..domain..")
        .and()
        .implement(resideInAPackage(basePackage + ".*.domain.model.."))
        .should()
        .resideInAPackage("..infrastructure..")
        .allowEmptyShould(true)
        .because(
            "Repository の実装は infrastructure.persistence へ、"
                + "外部システム interface の実装は infrastructure.client へ移す。");
  }

  private static ArchCondition<JavaClass> dependOnlyOnStandardTypesOrOwnPackage() {
    return new ArchCondition<>("depend only on java, org.jspecify, or the same package") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        for (final Dependency dependency : item.getDirectDependenciesFromSelf()) {
          final JavaClass target = dependency.getTargetClass().getBaseComponentType();
          final String targetPackage = target.getPackageName();
          final boolean allowed =
              target.isPrimitive()
                  || targetPackage.startsWith("java.")
                  || targetPackage.startsWith("org.jspecify.")
                  || targetPackage.equals(item.getPackageName());
          if (!allowed) {
            events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
          }
        }
      }
    };
  }
}
