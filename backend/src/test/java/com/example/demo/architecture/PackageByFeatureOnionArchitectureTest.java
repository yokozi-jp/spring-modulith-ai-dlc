package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/** Package by feature で分割した各機能モジュールの内部に、オニオンアーキテクチャを強制する。 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class PackageByFeatureOnionArchitectureTest {

  /** 機能ルートを公開契約として扱い、Presentation と各 Infrastructure Adapter から Domain へ依存を向ける。 */
  @ArchTest
  /* package */ static final ArchRule dependenciesPointInward =
      Architectures.onionArchitecture()
          .domainModels("com.example.demo.*.domain.model..")
          .domainServices("com.example.demo.*.domain.service..")
          .applicationServices("com.example.demo.*", "com.example.demo.*.application..")
          .adapter("presentation", "com.example.demo.*.presentation..")
          .adapter("persistence", "com.example.demo.*.infrastructure.persistence..")
          .adapter("messaging", "com.example.demo.*.infrastructure.messaging..")
          .adapter("external-client", "com.example.demo.*.infrastructure.client..")
          .ensureAllClassesAreContainedInArchitectureIgnoring("com.example.demo")
          .withOptionalLayers(true)
          .because(
              "機能モジュール内の依存は外側から内側へ向け、"
                  + "Presentation、Persistence、Messaging、外部 Client を相互に依存させない。");

  /** 機能ルートの公開契約から、いずれかの機能モジュールの内部パッケージへの依存を禁止する。 */
  @ArchTest
  /* package */ static final ArchRule moduleApiDoesNotExposeInternalTypes =
      noClasses()
          .that()
          .resideInAPackage("com.example.demo.*")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.example.demo.*.domain..",
              "com.example.demo.*.application..",
              "com.example.demo.*.presentation..",
              "com.example.demo.*.infrastructure..")
          .allowEmptyShould(true)
          .because("機能ルートには他モジュールへ公開する自己完結した契約だけを置き、内部型を露出させない。");

  /** jOOQ API と生成型を各機能の Persistence Adapter に閉じ込める。 */
  @ArchTest
  /* package */ static final ArchRule jooqIsOnlyUsedByPersistenceAdapters =
      noClasses()
          .that()
          .resideOutsideOfPackage("..infrastructure.persistence..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.jooq..", "com.example.demo.jooq..")
          .because(
              "jOOQ の DSLContext と生成型は Persistence Adapter 内で Domain 型へ変換し、"
                  + "公開契約、Application、Domain、Presentation へ漏らさない。");

  /** Domain を Spring、jOOQ、JPA、Jackson から独立させる。 */
  @ArchTest
  /* package */ static final ArchRule domainDoesNotDependOnFrameworks =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "org.jooq..",
              "com.example.demo.jooq..",
              "jakarta.persistence..",
              "com.fasterxml.jackson..")
          .allowEmptyShould(true)
          .because("Domain には業務モデルと業務規則だけを置き、技術詳細へ依存させない。");

  /** Spring MVC の Controller を Presentation に置く。 */
  @ArchTest
  /* package */ static final ArchRule controllersResideInPresentation =
      classes()
          .that()
          .areAnnotatedWith(Controller.class)
          .or()
          .areAnnotatedWith(RestController.class)
          .should()
          .resideInAPackage("..presentation..")
          .allowEmptyShould(true)
          .because("HTTP の入力と出力は各機能の Presentation に閉じ込める。");

  /** Spring の Service を Application に置き、Domain Service はフレームワーク非依存に保つ。 */
  @ArchTest
  /* package */ static final ArchRule servicesResideInApplication =
      classes()
          .that()
          .areAnnotatedWith(Service.class)
          .should()
          .resideInAPackage("..application..")
          .allowEmptyShould(true)
          .because("Spring が管理するユースケース実装は Application に置く。");

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

  /** クラス単位の {@code @Transactional} を禁止し、トランザクション境界をメソッドへ明示する。 */
  @ArchTest
  /* package */ static final ArchRule transactionalIsNotDeclaredAtClassLevel =
      noClasses()
          .should()
          .beAnnotatedWith(Transactional.class)
          .because("トランザクション境界は Application の public メソッドへ明示する。");

  /** メソッド単位のトランザクション境界を Application の public メソッドに置く。 */
  @ArchTest
  /* package */ static final ArchRule transactionalMethodsArePublicApplicationMethods =
      methods()
          .that()
          .areAnnotatedWith(Transactional.class)
          .should()
          .beDeclaredInClassesThat()
          .resideInAPackage("..application..")
          .andShould()
          .bePublic()
          .allowEmptyShould(true)
          .because("トランザクション境界はプロキシ方式に左右されない Application の public ユースケースへ明示する。");
}
