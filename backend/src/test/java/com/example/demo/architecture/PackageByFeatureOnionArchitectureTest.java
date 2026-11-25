package com.example.demo.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.ANNOTATIONS;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.Architectures;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Package by feature で分割した各機能モジュールの内部に、オニオンアーキテクチャとクラスの置き場所を強制する。
 *
 * <p>基底パッケージを含む規則は {@code xxxRule(basePackage)} のファクトリで組み立て、{@link ArchitectureRuleFixtureTest}
 * がフィクスチャのパッケージで同じ規則を再利用する。
 */
// ArchUnit の JUnit 5 エンジンが @ArchTest フィールドを読むため、規則ファクトリだけを持つクラスでもユーティリティクラスにしない。
@SuppressWarnings({
  "PMD.TestClassWithoutTestCases",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.UseUtilityClass"
})
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class PackageByFeatureOnionArchitectureTest {

  /** プロダクションコードの基底パッケージ。 */
  private static final String BASE_PACKAGE = DemoApplication.class.getPackageName();

  /** Domain のパッケージのパターン。 */
  private static final String DOMAIN_PACKAGE = "..domain..";

  /** Lombok が生成したコードに付ける注釈。テストの実行時のクラスパスに Lombok がないので、名前で比べる。 */
  private static final DescribedPredicate<JavaClass> LOMBOK_GENERATED =
      DescribedPredicate.describe(
          "lombok.Generated", javaClass -> "lombok.Generated".equals(javaClass.getName()));

  /** 基底パッケージに続く、各モジュールの Domain Model のパッケージ。 */
  private static final String MODULE_DOMAIN_MODEL = ".*.domain.model..";

  /** 基底パッケージに続く、各モジュールの Application のパッケージ。 */
  private static final String MODULE_APPLICATION = ".*.application..";

  /** Domain Model のパッケージ。 */
  private static final String DOMAIN_MODEL_PACKAGE = "..domain.model..";

  /**
   * Domain の外部システムのインタフェース。{@code domain.model} の interface のうち、Repository、アノテーション、{@code
   * package-info}、sealed interface（結果を表す値）以外。
   */
  /* package */ static final DescribedPredicate<JavaClass> EXTERNAL_SYSTEM_INTERFACE =
      INTERFACES
          .and(resideInAPackage(DOMAIN_MODEL_PACKAGE))
          .and(not(simpleNameEndingWith("Repository")))
          .and(not(ANNOTATIONS))
          .and(
              not(
                  DescribedPredicate.describe(
                      "sealed", javaClass -> javaClass.reflect().isSealed())))
          .and(not(simpleName("package-info")));

  /** パッケージ構造を決めた ADR のパス。 */
  private static final String ADR_002 = "docs/adr/ADR-002-package-by-feature-onion-architecture.md";

  /** クラスの役割を決めた ADR のパス。 */
  private static final String ADR_050 = "docs/adr/ADR-050-define-backend-class-roles-and-naming.md";

  /** shared モジュールを決めた ADR のパス。 */
  private static final String ADR_048 =
      "docs/adr/ADR-048-add-shared-module-for-jooq-common-code.md";

  /**
   * Domain が使ってよい標準型のパッケージ。{@code java..} 全体は許さず、{@code java.net.http}、{@code java.io}、{@code
   * java.nio} などを止める。
   */
  private static final List<String> DOMAIN_JAVA_PACKAGES =
      List.of("java.lang..", "java.util..", "java.math..", "java.time..");

  /** テナントの ID の型の名前の、基底パッケージより後ろ。 */
  private static final String TENANT_ID_TYPE = ".shared.tenancy.TenantId";

  /** Persistence Adapter のパッケージ。 */
  private static final String PERSISTENCE_PACKAGE = "..infrastructure.persistence..";

  /** jOOQ を使う shared のパッケージ。機能モジュールの Persistence Adapter と同じ層に入る。 */
  private static final List<String> SHARED_ADAPTER_PACKAGES =
      List.of("persistence", "eventresubmission");

  /** 層の責務の規約と、クラスの役割を決めた ADR。 */
  private static final String LAYERS_DOCS = "規約：docs/backend/layers.md、" + ADR_050;

  /** Repository とその実装を Persistence Adapter に置く規則の理由（jooq-repository.md の定義と ADR-002）。 */
  private static final String PERSISTENCE_ADAPTER_REASON =
      "jOOQ と生成型による DB の実装を infrastructure.persistence に閉じ込め、Domain と Application を DB の技術詳細から独立させるため。";

  /** トランザクション境界の規則の理由（command-handler.md の定義）。 */
  private static final String TRANSACTION_BOUNDARY =
      "状態を変えるユースケースの処理の順序とトランザクション境界を一か所で決め、"
          + "一つのユースケースを Command の受け取りから Result の返却まで一つのトランザクションで進めるため。";

  /** トランザクション境界の規約と、クラスの役割を決めた ADR。 */
  private static final String TRANSACTION_DOCS =
      "規約：docs/backend/layers.md、docs/backend/class-roles/command-handler.md、" + ADR_050;

  /** 機能ルートを公開契約として扱い、Presentation と各 Infrastructure Adapter から Domain へ依存を向ける。 */
  @ArchTest
  /* package */ static final ArchRule dependenciesPointInward =
      dependenciesPointInwardRule(BASE_PACKAGE);

  /** Infrastructure Adapter が使う機能モジュールの型を、同じモジュールの Domain Model に限る。 */
  @ArchTest
  /* package */ static final ArchRule infrastructureDependsOnlyOnDomainModel =
      infrastructureDependsOnlyOnDomainModelRule(BASE_PACKAGE);

  /** shared の永続化の共通処理を使うクラスを、他のモジュールの Persistence Adapter に限る。 */
  @ArchTest
  /* package */ static final ArchRule sharedModuleIsUsedOnlyByPersistenceAdapters =
      sharedModuleIsUsedOnlyByPersistenceAdaptersRule(BASE_PACKAGE);

  /** shared、domain、application、infrastructure.persistence から HTTP の型への依存を禁止する。 */
  @ArchTest
  /* package */ static final ArchRule httpTypesAreNotUsedByInnerLayers =
      httpTypesAreNotUsedByInnerLayersRule(BASE_PACKAGE);

  /** Problem Details と HTTP の例外の対応づけを {@code error.presentation.web} の外で書かせない。 */
  @ArchTest
  /* package */ static final ArchRule httpErrorsAreMappedOnlyInErrorPresentationWeb =
      httpErrorsAreMappedOnlyInErrorPresentationWebRule(BASE_PACKAGE);

  /** 422 の理由を、ベースパッケージの下のモジュールに置いた enum に限る。 */
  @ArchTest
  /* package */ static final ArchRule failureReasonsAreModuleEnums =
      failureReasonsAreModuleEnumsRule(BASE_PACKAGE);

  /** 機能ルートの公開契約は、標準型、JSpecify、同じルートパッケージの型だけに依存させる。 */
  @ArchTest
  /* package */ static final ArchRule moduleApiDoesNotExposeInternalTypes =
      moduleApiDoesNotExposeInternalTypesRule(BASE_PACKAGE);

  /** DB 技術 API を各機能の Persistence Adapter に閉じ込める。 */
  @ArchTest
  /* package */ static final ArchRule databaseTechnologyApisAreOnlyUsedByPersistenceAdapters =
      noClasses()
          .that()
          .resideOutsideOfPackages(persistenceAdapterPackages(BASE_PACKAGE))
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.jooq..",
              "com.example.demo.jooq..",
              "java.sql..",
              "javax.sql..",
              "org.springframework.jdbc..")
          .because(
              "Domain と Application を DB の技術詳細から独立させるため。"
                  + "直し方：jOOQ、JDBC、生成型を使う処理は Jooq<Aggregate>Repository へ移し、"
                  + "Persistence Adapter の中で Domain の型へ変換する。"
                  + "規約：docs/backend/class-roles/jooq-repository.md、"
                  + ADR_002);

  /** Domain Model の依存先を許可した型だけに限る。 */
  @ArchTest
  /* package */ static final ArchRule domainModelDependsOnlyOnAllowedTypes =
      domainModelDependsOnlyOnAllowedTypesRule(BASE_PACKAGE);

  /** Application の依存先を、許可した外部ライブラリの型とこのアプリケーションの型に限る。 */
  @ArchTest
  /* package */ static final ArchRule applicationDependsOnlyOnAllowedTypes =
      applicationDependsOnlyOnAllowedTypesRule(BASE_PACKAGE);

  /** 値オブジェクトの不変条件の違反を、422 になる業務上の失敗ではなく {@link IllegalArgumentException} で表す。 */
  @ArchTest
  /* package */ static final ArchRule valueObjectsDoNotThrowBusinessRuleViolation =
      noClasses()
          .that()
          .resideInAPackage(DOMAIN_MODEL_PACKAGE)
          .and()
          .areRecords()
          .should()
          .dependOnClassesThat(simpleName("BusinessRuleViolationException"))
          .allowEmptyShould(true)
          .because(
              "値オブジェクトの違反は、利用者が直せない状態であり、422 にするとプログラムの誤りが隠れるため。"
                  + "直し方：不変条件の違反には IllegalArgumentException を投げ、"
                  + "状態に依存する業務規則は集約か Domain Service で BusinessRuleViolationException にする。"
                  + "規約：docs/backend/class-roles/value-object.md、docs/backend/class-roles/business-exception.md");

  /**
   * Domain Service は標準型、JSpecify、Lombok、Domain、shared.failure、TenantId と {@code @Service} だけに依存させる。
   */
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
          .because(
              "@Bean で登録すると Domain Service を足すたびに設定クラスの変更が要るため、"
                  + "コンポーネントスキャンで登録する。"
                  + "直し方：domain.service のトップレベルのクラスに @Service を付け、依存はコンストラクタで受け取る。"
                  + "規約：docs/backend/class-roles/domain-service.md、"
                  + ADR_050);

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
              "Application のユースケースと Domain Service だけが @Service を名乗り、Service という名前から役割を区別できるようにするため。"
                  + "直し方：@Service のクラスは application（CommandHandler、QueryService、Listener）か "
                  + "domain.service へ移す。Adapter の Bean には @Repository か @Component を付ける。"
                  + LAYERS_DOCS);

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
          .because(
              "HTTP の入力と出力をユースケースの入力と出力に変換する場所を、集約ごとに一つに決めるため。"
                  + "直し方：Controller は <モジュール>.presentation.web へ移し、<Aggregate>Controller と命名する。"
                  + "規約：docs/backend/class-roles/controller.md、"
                  + ADR_050);

  /** Spring の Repository を Persistence Adapter に置く。 */
  @ArchTest
  /* package */ static final ArchRule repositoriesResideInPersistenceAdapters =
      classes()
          .that()
          .areAnnotatedWith(Repository.class)
          .should()
          .resideInAPackage(PERSISTENCE_PACKAGE)
          .allowEmptyShould(true)
          .because(
              PERSISTENCE_ADAPTER_REASON
                  + "直し方：@Repository のクラスは <モジュール>.infrastructure.persistence へ移し、"
                  + "Jooq<Aggregate>Repository にする。"
                  + "規約：docs/backend/class-roles/jooq-repository.md、"
                  + ADR_050
                  + "、"
                  + ADR_002);

  /** Presentation から Domain への依存を禁止する。 */
  @ArchTest
  /* package */ static final ArchRule presentationDoesNotDependOnDomain =
      noClasses()
          .that()
          .resideInAPackage("..presentation..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage(DOMAIN_PACKAGE)
          .allowEmptyShould(true)
          .because(
              "Domain の型を Application の外へ出さず、HTTP API の形と Domain を独立に変えられるようにするため。"
                  + "直し方：Presentation では Application の Command、Result、CommandHandler と、"
                  + "モジュールルートの Queries と record だけを使い、"
                  + "Domain の型との変換は Application の CommandHandler か QueryService の中で行う。"
                  + "規約：docs/backend/class-roles/controller.md、docs/backend/class-roles/request.md、"
                  + "docs/backend/class-roles/response.md、"
                  + ADR_050);

  /** Domain の interface を Domain の外で実装するクラスは Infrastructure に置く。 */
  @ArchTest
  /* package */ static final ArchRule domainInterfacesAreImplementedInInfrastructure =
      domainInterfacesAreImplementedInInfrastructureRule(BASE_PACKAGE);

  /** Domain の Repository の実装は {@code Jooq<Aggregate>Repository} として Persistence Adapter に置く。 */
  @ArchTest
  /* package */ static final ArchRule repositoryImplementationsAreJooqRepositories =
      classes()
          .that()
          .implement(simpleNameEndingWith("Repository").and(resideInAPackage(DOMAIN_MODEL_PACKAGE)))
          .should()
          .haveSimpleNameStartingWith("Jooq")
          .andShould()
          .haveSimpleNameEndingWith("Repository")
          .andShould()
          .resideInAPackage(PERSISTENCE_PACKAGE)
          .allowEmptyShould(true)
          .because(
              PERSISTENCE_ADAPTER_REASON
                  + "直し方：Repository の実装は infrastructure.persistence へ移し、"
                  + "Jooq<Aggregate>Repository と命名する。"
                  + "規約：docs/backend/class-roles/jooq-repository.md、"
                  + ADR_050
                  + "、"
                  + ADR_002);

  /** Domain の外部システム interface の実装は {@code <ExternalSystem>Client} として Client Adapter に置く。 */
  @ArchTest
  /* package */ static final ArchRule externalSystemImplementationsAreClients =
      classes()
          .that()
          .resideOutsideOfPackage(DOMAIN_PACKAGE)
          .and()
          .implement(EXTERNAL_SYSTEM_INTERFACE)
          .should()
          .haveSimpleNameEndingWith("Client")
          .andShould()
          .resideInAPackage("..infrastructure.client..")
          .allowEmptyShould(true)
          .because(
              "URL、JSON の形、タイムアウト、サーキットブレーカー、リトライを Client だけが扱い、"
                  + "CommandHandler が外部システムの HTTP の詳細を知らずに済むようにするため。"
                  + "直し方：外部システムのインタフェースの実装は infrastructure.client へ移し、"
                  + "<ExternalSystem>Client と命名する。"
                  + "規約：docs/backend/class-roles/external-client.md、"
                  + ADR_050);

  /** クラス単位の {@code @Transactional} を、合成アノテーション経由も含めて禁止する。 */
  @ArchTest
  /* package */ static final ArchRule transactionalIsNotDeclaredAtClassLevel =
      noClasses()
          .should()
          .beMetaAnnotatedWith(Transactional.class)
          .because(
              TRANSACTION_BOUNDARY
                  + "直し方：クラスの @Transactional を外し、Application の public メソッドへ付け直す。"
                  + TRANSACTION_DOCS);

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
              TRANSACTION_BOUNDARY
                  + "直し方：@Transactional と @ApplicationModuleListener は Application の public メソッドへ移す。"
                  + TRANSACTION_DOCS);

  /** 基底パッケージ配下の各機能モジュールに、外側から内側へ向かう依存を強制する規則を組み立てる。 */
  /* package */ static ArchRule dependenciesPointInwardRule(final String basePackage) {
    return Architectures.onionArchitecture()
        .domainModels(basePackage + MODULE_DOMAIN_MODEL)
        .domainServices(basePackage + ".*.domain.service..")
        .applicationServices(basePackage + ".*", basePackage + MODULE_APPLICATION)
        .adapter("presentation", basePackage + ".*.presentation..")
        .adapter("persistence", persistenceAdapterPackages(basePackage))
        .adapter("external-client", basePackage + ".*.infrastructure.client..")
        .ensureAllClassesAreContainedInArchitectureIgnoring(basePackage, basePackage + ".shared..")
        .withOptionalLayers(true)
        .because(
            "Domain と Application を Web、DB、外部 API の技術詳細から独立させ、テストと変更をしやすくするため。"
                + "直し方：依存を外側から内側へ向け、Presentation、Persistence、外部 Client を相互に依存させない。"
                + "どの層にも属さないパッケージのクラスは、役割に合う層のパッケージへ移す。"
                + "規約：docs/backend/architecture.md、"
                + ADR_002);
  }

  /**
   * Infrastructure Adapter から Application、Domain Service、モジュールルートへの依存を禁止する規則を組み立てる。
   *
   * <p>オニオン規則は Adapter から内側の層への依存をすべて許すため、この規則で Domain Model 以外を閉じる。 基底パッケージ直下の jOOQ
   * 生成型のパッケージはモジュールルートではないので除く。
   */
  /* package */ static ArchRule infrastructureDependsOnlyOnDomainModelRule(
      final String basePackage) {
    return noClasses()
        .that()
        .resideInAPackage(basePackage + ".*.infrastructure..")
        .should()
        .dependOnClassesThat(
            resideInAnyPackage(
                    basePackage + MODULE_APPLICATION, basePackage + ".*.domain.service..")
                .or(
                    resideInAPackage(basePackage + ".*")
                        .and(not(resideInAPackage(basePackage + ".jooq")))))
        .allowEmptyShould(true)
        .because(
            "Adapter がユースケースを呼べると、Presentation のほかに処理の入口ができ、"
                + "トランザクション境界が Application の外にも広がるため。"
                + "直し方：Infrastructure では同じモジュールの domain.model の型だけを使い、"
                + "Application、Domain Service、モジュールルートの型を使う処理は Application の CommandHandler か"
                + " QueryService へ移す。"
                + "規約：docs/backend/class-roles/jooq-repository.md、docs/backend/class-roles/external-client.md、"
                + ADR_050);
  }

  /**
   * 永続化の技術的な共通処理を置く shared のパッケージ（{@link #SHARED_ADAPTER_PACKAGES}）に依存するクラスを、{@code
   * infrastructure.persistence} に限る規則を組み立てる。
   *
   * <p>shared 自身の中の依存は対象にしない。使う場所を限るパッケージだけを名前で持ち、ほかの shared のパッケージは、どの層からも使える。
   */
  /* package */ static ArchRule sharedModuleIsUsedOnlyByPersistenceAdaptersRule(
      final String basePackage) {
    return noClasses()
        .that()
        .resideOutsideOfPackages(basePackage + ".shared..", PERSISTENCE_PACKAGE)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(sharedAdapterPackages(basePackage))
        .because(
            "shared の永続化と再投入の共通処理は技術的な処理であり、"
                + "使う場所を他のモジュールの infrastructure.persistence に限って、"
                + "業務の処理が共通処理と共通カラムに依存しないようにするため。"
                + "直し方：shared の永続化と再投入の型を使う処理を <モジュール>.infrastructure.persistence の "
                + "Jooq<Aggregate>Repository へ移し、Application と Domain からは Repository を通して使う。"
                + "規約：docs/backend/shared-module.md、"
                + ADR_048);
  }

  /**
   * shared と各モジュールの {@code domain}、{@code application}、{@code infrastructure.persistence} が、Spring
   * Web、Spring の HTTP、Servlet の型に依存することを禁止する規則を組み立てる。
   */
  /* package */ static ArchRule httpTypesAreNotUsedByInnerLayersRule(final String basePackage) {
    return noClasses()
        .that()
        .resideInAnyPackage(
            basePackage + ".shared..",
            basePackage + ".*.domain..",
            basePackage + MODULE_APPLICATION,
            basePackage + ".*.infrastructure.persistence..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
        .because(
            "HTTP の型を使うのは presentation.web、infrastructure.client、ベースパッケージ直下の設定に限り、"
                + "業務の失敗が ADR-062 の例外と ApiExceptionHandler を通らずに HTTP のステータスになることを防ぐため。"
                + "直し方：業務上の失敗は shared.failure の NotFoundException、BusinessRuleViolationException と "
                + "shared.concurrency の ConflictException で表し、"
                + "HTTP のステータスへの対応づけは error の ApiExceptionHandler に置く。"
                + "規約：docs/backend/layers.md、docs/backend/shared-module.md、"
                + "docs/backend/class-roles/business-exception.md");
  }

  /**
   * {@code error.presentation.web} の外で、Problem Details の型、HTTP のステータスを持つ Spring
   * の例外、例外を応答へ変える注釈に依存することと、409 と 422 の {@link HttpStatus} の定数を読むことを禁止する規則を組み立てる。
   *
   * <p>注釈を付けることも依存に数え、入れ子の型（{@code ErrorResponse.Builder} など）も含める。 整数で書いた status（{@code
   * sendError(422)}、{@code ResponseEntity.status(409)}）は検出しない。
   */
  /* package */ static ArchRule httpErrorsAreMappedOnlyInErrorPresentationWebRule(
      final String basePackage) {
    return noClasses()
        .that()
        .resideInAPackage(basePackage + "..")
        .and()
        .resideOutsideOfPackage(basePackage + ".error.presentation.web..")
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(
            ProblemDetail.class,
            ErrorResponse.class,
            ErrorResponseException.class,
            ResponseStatusException.class,
            ExceptionHandler.class,
            ControllerAdvice.class,
            RestControllerAdvice.class,
            ResponseStatus.class)
        .orShould()
        .accessField(HttpStatus.class, HttpStatus.CONFLICT.name())
        .orShould()
        .accessField(HttpStatus.class, HttpStatus.UNPROCESSABLE_CONTENT.name())
        .because(
            "失敗から status、problem type、title、ログへの対応づけを ApiExceptionHandler の 1 か所に置き、"
                + "422 と 409 の type が about:blank に戻ることと、ログの記録が食い違うことを防ぐため。"
                + "成功の応答の @ResponseStatus（CREATED など）も禁じる。"
                + "直し方：業務上の失敗は shared.failure の BusinessRuleViolationException と NotFoundException、"
                + "shared.concurrency の ConflictException で投げ、422 の理由はモジュールの FailureReason の enum で渡す。"
                + "成功の status は ResponseEntity.created や noContent で返す。"
                + "例外の変換と Problem Details の組み立ては error.presentation.web の ApiExceptionHandler と "
                + "ApiProblemDetails に置く。"
                + "規約：docs/backend/class-roles/business-exception.md、docs/web-api/status-codes.md");
  }

  /**
   * {@code shared.failure.FailureReason} を実装する型を、ベースパッケージの下のモジュールに置いた enum に限る規則を組み立てる。
   *
   * <p>フィクスチャは自分の {@code shared.failure} に同じ名前の interface を持つため、型を名前で指す。
   */
  /* package */ static ArchRule failureReasonsAreModuleEnumsRule(final String basePackage) {
    return classes()
        .that()
        .implement(basePackage + ".shared.failure.FailureReason")
        .should()
        .beEnums()
        .andShould()
        .resideInAPackage(basePackage + ".*..")
        .allowEmptyShould(true)
        .because(
            "problem type は理由を置いたモジュールの名前と enum の定数の名前から作り、"
                + "enum でない型やモジュールの外の型では type が定まらないため。"
                + "直し方：理由を enum にし、com.example.demo.<module> の下の、理由を投げる層のパッケージに置く。"
                + "規約：docs/backend/class-roles/business-exception.md、"
                + "docs/adr/ADR-127-derive-problem-types-from-failure-reasons.md");
  }

  /**
   * 機能ルートの型が標準型、JSpecify、同じルートパッケージの型だけに依存することを強制する規則を組み立てる。
   *
   * <p>{@code package-info} は、モジュールの宣言（{@code @ApplicationModule}）を持つ型で公開契約ではないため、対象から除く。
   */
  /* package */ static ArchRule moduleApiDoesNotExposeInternalTypesRule(final String basePackage) {
    return classes()
        .that()
        .resideInAPackage(basePackage + ".*")
        .and()
        .doNotHaveSimpleName("package-info")
        .should(dependOnlyOnStandardTypesOrOwnPackage(basePackage))
        .allowEmptyShould(true)
        .because(
            "モジュールルートの型は他モジュールが読む公開契約であり、内部の型を持たせると他モジュールがその型に依存するため。"
                + "直し方：項目を String、Instant、BigDecimal などの標準型か、"
                + "同じルートパッケージの record と enum に置き換える。"
                + "例外は、イベントが運ぶテナントの shared.tenancy.TenantId と shared.tenancy.TenantScoped だけである。"
                + "規約：docs/backend/architecture.md、"
                + ADR_050);
  }

  /**
   * Domain Model の依存先を許可した型だけに限る規則を組み立てる。許可する型は、この規則の一覧と {@code docs/backend/architecture-tests.md}
   * が持つ。
   */
  /* package */ static ArchRule domainModelDependsOnlyOnAllowedTypesRule(final String basePackage) {
    return classes()
        .that()
        .resideInAPackage(basePackage + MODULE_DOMAIN_MODEL)
        .should()
        .onlyDependOnClassesThat(
            allowedInDomain(
                basePackage,
                basePackage + MODULE_DOMAIN_MODEL,
                basePackage + ".shared.concurrency..",
                basePackage + ".shared.failure.."))
        .allowEmptyShould(true)
        .because(
            "業務モデルと業務規則を、フレームワークと外部システムの SDK の型から独立させ、テストと変更をしやすくするため。"
                + "直し方：domain.model から許可していない型とアノテーションを取り除き、"
                + "技術の処理は Application か Infrastructure へ移す。"
                + "外部システムの SDK と生成型は infrastructure.client の中に閉じ、domain.model の型へ写す。"
                + "許可する型を足すときは、この規則の一覧と docs/backend/architecture-tests.md を直す。"
                + "規約：docs/backend/layers.md、docs/backend/class-roles/external-client.md、"
                + ADR_002);
  }

  /** Application の依存先を、許可した外部ライブラリの型とこのアプリケーションの型に限る規則を組み立てる。 */
  /* package */ static ArchRule applicationDependsOnlyOnAllowedTypesRule(final String basePackage) {
    return classes()
        .that()
        .resideInAPackage(basePackage + MODULE_APPLICATION)
        .should()
        .onlyDependOnClassesThat(
            resideInAnyPackage(
                    "java..",
                    "org.jspecify..",
                    "org.slf4j..",
                    "org.springframework.stereotype..",
                    "org.springframework.transaction.annotation..",
                    "org.springframework.modulith.events..",
                    "org.springframework.context",
                    basePackage + "..")
                .or(type(PreAuthorize.class))
                .or(LOMBOK_GENERATED))
        .allowEmptyShould(true)
        .because(
            "外部システムの SDK、生成型、HTTP の型が Application の引数と戻り値に出ると、"
                + "ユースケースが外部の技術詳細に結びつくため。"
                + "直し方：外部ライブラリの型は infrastructure の中に閉じ、Application では Domain の型を使う。"
                + "許可する型を足すときは、この規則の一覧と docs/backend/architecture-tests.md を直す。"
                + "規約：docs/backend/layers.md、docs/backend/class-roles/command-handler.md、"
                + ADR_002);
  }

  /**
   * Domain Service の依存先を標準型、JSpecify、Lombok、Domain、{@code shared.failure}、{@code
   * TenantId}、{@code @Service} に限る規則を組み立てる。
   */
  /* package */ static ArchRule domainServicesDependOnlyOnDomainAndJavaRule(
      final String basePackage) {
    return classes()
        .that()
        .resideInAPackage("..domain.service..")
        .should()
        .onlyDependOnClassesThat(
            allowedInDomain(
                    basePackage, basePackage + ".*.domain..", basePackage + ".shared.failure..")
                .or(type(Service.class)))
        .allowEmptyShould(true)
        .because(
            "Domain Service には業務規則だけを置き、イベント発行、外部呼び出し、ログ出力は CommandHandler の役割とするため。"
                + "直し方：Domain の型と標準型だけで業務規則を書き、"
                + "イベント発行、外部呼び出し、ログ出力は Application の CommandHandler へ移す。"
                + "規約：docs/backend/class-roles/domain-service.md、"
                + ADR_050);
  }

  /** Domain の外で Domain Model の interface を実装するクラスを Infrastructure に置く規則を組み立てる。 */
  /* package */ static ArchRule domainInterfacesAreImplementedInInfrastructureRule(
      final String basePackage) {
    return classes()
        .that()
        .resideOutsideOfPackage(DOMAIN_PACKAGE)
        .and()
        .implement(resideInAPackage(basePackage + MODULE_DOMAIN_MODEL))
        .should()
        .resideInAPackage("..infrastructure..")
        .allowEmptyShould(true)
        .because(
            "DDD とオニオンアーキテクチャに従い、インタフェースを Domain の語彙で domain.model に定義し、"
                + "実装を Infrastructure に置くため。"
                + "直し方：Repository の実装は infrastructure.persistence へ、"
                + "外部システムのインタフェースの実装は infrastructure.client へ移す。"
                + LAYERS_DOCS);
  }

  /**
   * Domain が依存してよい型の述語を返す。
   *
   * <p>標準型は {@link #DOMAIN_JAVA_PACKAGES}、ライブラリは JSpecify と Lombok、このアプリケーションの型は {@code
   * shared.tenancy.TenantId} と {@code layerPackages} だけである。{@code shared.tenancy.TenantContext} は
   * ScopedValue の暗黙の文脈で、Domain が読むと引数に現れない入力になるため許さない。
   */
  private static DescribedPredicate<JavaClass> allowedInDomain(
      final String basePackage, final String... layerPackages) {
    return resideInAnyPackage(DOMAIN_JAVA_PACKAGES.toArray(String[]::new))
        .or(resideInAnyPackage("org.jspecify..", "lombok.."))
        .or(resideInAnyPackage(layerPackages))
        .or(
            DescribedPredicate.describe(
                "shared.tenancy.TenantId",
                javaClass ->
                    (basePackage + ".shared.tenancy.TenantId").equals(javaClass.getName())));
  }

  /** {@link #SHARED_ADAPTER_PACKAGES} を、{@code resideInAnyPackage} に渡す形で返す。 */
  private static String[] sharedAdapterPackages(final String basePackage) {
    return SHARED_ADAPTER_PACKAGES.stream()
        .map(name -> basePackage + ".shared." + name + "..")
        .toArray(String[]::new);
  }

  /** 機能モジュールの Persistence Adapter と、{@link #SHARED_ADAPTER_PACKAGES} のパッケージを返す。 */
  private static String[] persistenceAdapterPackages(final String basePackage) {
    return Stream.concat(
            Stream.of(basePackage + ".*.infrastructure.persistence.."),
            Arrays.stream(sharedAdapterPackages(basePackage)))
        .toArray(String[]::new);
  }

  private static ArchCondition<JavaClass> dependOnlyOnStandardTypesOrOwnPackage(
      final String basePackage) {
    final Set<String> tenantTypes =
        Set.of(basePackage + TENANT_ID_TYPE, basePackage + ".shared.tenancy.TenantScoped");
    return new ArchCondition<>(
        "depend only on java, org.jspecify, shared.tenancy.TenantId, shared.tenancy.TenantScoped,"
            + " or the same package") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        for (final Dependency dependency : item.getDirectDependenciesFromSelf()) {
          final JavaClass target = dependency.getTargetClass().getBaseComponentType();
          final String targetPackage = target.getPackageName();
          final boolean allowed =
              target.isPrimitive()
                  || targetPackage.startsWith("java.")
                  || targetPackage.startsWith("org.jspecify.")
                  || tenantTypes.contains(target.getName())
                  || targetPackage.equals(item.getPackageName());
          if (!allowed) {
            events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
          }
        }
      }
    };
  }
}
