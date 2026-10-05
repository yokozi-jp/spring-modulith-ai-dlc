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

  /** パッケージ構造を決めた ADR のパス。 */
  private static final String ADR_002 = "docs/adr/ADR-002-package-by-feature-onion-architecture.md";

  /** クラスの役割を決めた ADR のパス。 */
  private static final String ADR_050 = "docs/adr/ADR-050-define-backend-class-roles-and-naming.md";

  /** shared モジュールを決めた ADR のパス。 */
  private static final String ADR_048 =
      "docs/adr/ADR-048-add-shared-module-for-jooq-common-code.md";

  /** Persistence Adapter のパッケージ。 */
  private static final String PERSISTENCE_PACKAGE = "..infrastructure.persistence..";

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

  /** shared モジュールを使うクラスを、他のモジュールの Persistence Adapter に限る。 */
  @ArchTest
  /* package */ static final ArchRule sharedModuleIsUsedOnlyByPersistenceAdapters =
      sharedModuleIsUsedOnlyByPersistenceAdaptersRule(BASE_PACKAGE);

  /** 機能ルートの公開契約は、標準型、JSpecify、同じルートパッケージの型だけに依存させる。 */
  @ArchTest
  /* package */ static final ArchRule moduleApiDoesNotExposeInternalTypes =
      moduleApiDoesNotExposeInternalTypesRule(BASE_PACKAGE);

  /** DB 技術 API を各機能の Persistence Adapter に閉じ込める。 */
  @ArchTest
  /* package */ static final ArchRule databaseTechnologyApisAreOnlyUsedByPersistenceAdapters =
      noClasses()
          .that()
          .resideOutsideOfPackage(PERSISTENCE_PACKAGE)
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
          .because(
              "業務モデルと業務規則を技術詳細から独立させ、テストと変更をしやすくするため。"
                  + "直し方：domain.model からフレームワークの型とアノテーションを取り除き、"
                  + "技術の処理は Application か Infrastructure へ移す。"
                  + "規約：docs/backend/layers.md、"
                  + ADR_002);

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
          .resideInAPackage("..domain..")
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
          .implement(simpleNameEndingWith("Repository").and(resideInAPackage("..domain.model..")))
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
          .resideOutsideOfPackage("..domain..")
          .and()
          .implement(
              resideInAPackage("..domain.model..").and(not(simpleNameEndingWith("Repository"))))
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
        .domainModels(basePackage + ".*.domain.model..")
        .domainServices(basePackage + ".*.domain.service..")
        .applicationServices(basePackage + ".*", basePackage + ".*.application..")
        .adapter("presentation", basePackage + ".*.presentation..")
        .adapter("persistence", basePackage + ".*.infrastructure.persistence..")
        .adapter("external-client", basePackage + ".*.infrastructure.client..")
        .ensureAllClassesAreContainedInArchitectureIgnoring(
            basePackage, basePackage + ".shared.concurrency..")
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
                    basePackage + ".*.application..", basePackage + ".*.domain.service..")
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
   * shared の外で shared の型に依存するクラスを、{@code infrastructure.persistence} に限る規則を組み立てる。
   *
   * <p>shared 自身の中の依存は対象にしない。楽観的ロックの語彙を置く {@code shared.concurrency} は、どの層からも使えるため対象にしない。
   */
  /* package */ static ArchRule sharedModuleIsUsedOnlyByPersistenceAdaptersRule(
      final String basePackage) {
    final String sharedPackage = basePackage + ".shared..";
    final String concurrencyPackage = basePackage + ".shared.concurrency..";
    return noClasses()
        .that()
        .resideOutsideOfPackages(sharedPackage, PERSISTENCE_PACKAGE)
        .should()
        .dependOnClassesThat(
            resideInAPackage(sharedPackage).and(not(resideInAPackage(concurrencyPackage))))
        .because(
            "shared は永続化の技術的な共通処理を置くモジュールであり、"
                + "使う場所を他のモジュールの infrastructure.persistence に限って、"
                + "業務の処理が共通処理と共通カラムに依存しないようにするため。"
                + "どの層からも使ってよいのは、楽観的ロックの語彙を置く shared.concurrency だけである。"
                + "直し方：shared の型を使う処理を <モジュール>.infrastructure.persistence の "
                + "Jooq<Aggregate>Repository へ移し、Application と Domain からは Repository を通して使う。"
                + "規約：docs/backend/architecture.md、"
                + ADR_048);
  }

  /** 機能ルートの型が標準型、JSpecify、同じルートパッケージの型だけに依存することを強制する規則を組み立てる。 */
  /* package */ static ArchRule moduleApiDoesNotExposeInternalTypesRule(final String basePackage) {
    return classes()
        .that()
        .resideInAPackage(basePackage + ".*")
        .should(dependOnlyOnStandardTypesOrOwnPackage())
        .allowEmptyShould(true)
        .because(
            "モジュールルートの型は他モジュールが読む公開契約であり、内部の型を持たせると他モジュールがその型に依存するため。"
                + "直し方：項目を String、Instant、BigDecimal などの標準型か、"
                + "同じルートパッケージの record と enum に置き換える。"
                + "規約：docs/backend/architecture.md、"
                + ADR_050);
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
        .resideOutsideOfPackage("..domain..")
        .and()
        .implement(resideInAPackage(basePackage + ".*.domain.model.."))
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
