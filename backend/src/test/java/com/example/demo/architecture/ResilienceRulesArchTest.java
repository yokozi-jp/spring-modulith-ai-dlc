package com.example.demo.architecture;

import static com.example.demo.architecture.ClassRoleArchTest.APPLICATION_LISTENER;
import static com.example.demo.architecture.ClassRoleArchTest.COMMAND_HANDLER_USING_EXTERNAL_SYSTEM;
import static com.example.demo.architecture.ClassRoleArchTest.LISTENER_DOC;
import static com.example.demo.architecture.ClassRoleArchTest.conventions;
import static com.example.demo.architecture.PackageByFeatureOnionArchitectureTest.CLIENT;
import static com.example.demo.architecture.PackageByFeatureOnionArchitectureTest.CLIENT_PACKAGE;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.resilience.annotation.Retryable;

/** 外部システムの呼び出しの再試行と circuit breaker を、ADR-019 と ADR-079 の置き場所に限る。 */
// ArchUnit の JUnit 5 エンジンが @ArchTest のフィールドを読むため、@Test のメソッドを持たない。
// 規則が他のアーキテクチャテストの述語と規約のパスを共有するため、静的 import が多い。
@SuppressWarnings({"PMD.TestClassWithoutTestCases", "PMD.TooManyStaticImports"})
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class ResilienceRulesArchTest {

  /** 耐障害性と容量の上限を決めた ADR のパス。 */
  private static final String ADR_019 =
      "docs/adr/ADR-019-define-resilience-and-capacity-guardrails.md";

  /** 失敗したイベント出版を定期に再投入すると決めた ADR のパス。 */
  private static final String ADR_079 =
      "docs/adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md";

  /** 外部システムの Client の規約のパス。 */
  private static final String EXTERNAL_CLIENT_DOC = "docs/backend/class-roles/external-client.md";

  /** Spring Framework の、プログラムで再試行する API（{@code RetryTemplate} など）のパッケージ。 */
  private static final String SPRING_CORE_RETRY = "org.springframework.core.retry..";

  /** 外部システムを呼ぶ経路のクラス。Client、外部システムを呼ぶ CommandHandler、Listener。 */
  private static final DescribedPredicate<JavaClass> EXTERNAL_SYSTEM_PATH =
      resideInAPackage(CLIENT_PACKAGE)
          .or(COMMAND_HANDLER_USING_EXTERNAL_SYSTEM)
          .or(APPLICATION_LISTENER);

  /** {@code infrastructure.client} の {@code *Client}。 */
  private static final DescribedPredicate<JavaClass> CLIENT_CLASS =
      resideInAPackage(CLIENT_PACKAGE).and(simpleNameEndingWith(CLIENT));

  /** Resilience4j の retry と TimeLimiter を使わない。 */
  @ArchTest
  /* package */ static final ArchRule resilience4jRetryAndTimeLimiterAreNotUsed =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.github.resilience4j.retry..", "io.github.resilience4j.timelimiter..")
          .allowEmptyShould(true)
          .because(
              "外部の呼び出しをやり直す層はイベント出版の再投入だけにし、タイムアウトは HTTP クライアントの接続と呼び出しに持つため。"
                  + "Client の中で再試行すると、Listener のトランザクションの中で同じ要求を重ね、再投入の回数と掛け合わさる。"
                  + "直し方：@Retry、@TimeLimiter、Retry と TimeLimiter の API を外し、失敗は例外で投げてイベント出版を FAILED に残し、"
                  + "再投入でやり直す。タイムアウトは Client の connect-timeout と read-timeout で決める。"
                  + conventions(EXTERNAL_CLIENT_DOC, ADR_019, ADR_079));

  /** Spring の {@code @Retryable}（合成アノテーションを含む）と {@code RetryTemplate} を、外部システムを呼ぶ経路で使わない。 */
  @ArchTest
  /* package */ static final ArchRule springRetryIsNotUsedOnExternalSystemPath =
      CompositeArchRule.of(
              noClasses()
                  .that(EXTERNAL_SYSTEM_PATH)
                  .should()
                  .beMetaAnnotatedWith(Retryable.class)
                  .orShould()
                  .dependOnClassesThat()
                  .resideInAPackage(SPRING_CORE_RETRY))
          .and(
              noMethods()
                  .that()
                  .areDeclaredInClassesThat(EXTERNAL_SYSTEM_PATH)
                  .should()
                  .beMetaAnnotatedWith(Retryable.class))
          .allowEmptyShould(true)
          .because(
              "外部の呼び出しをやり直す層はイベント出版の再投入だけにするため。"
                  + "@Retryable は @EnableResilientMethods がないと黙って効かず、有効にすると RetryTemplate と同じく"
                  + "再投入と重なって同じ要求を重ねる。"
                  + "直し方：@Retryable と RetryTemplate を外し、失敗は例外で投げてイベント出版を FAILED に残し、再投入でやり直す。"
                  + conventions(EXTERNAL_CLIENT_DOC, LISTENER_DOC, ADR_019, ADR_079));

  /**
   * Resilience4j の {@code @CircuitBreaker} は、{@code infrastructure.client} の {@code *Client}
   * のクラスかメソッドにだけ付ける。
   */
  @ArchTest
  /* package */ static final ArchRule circuitBreakerIsUsedOnlyOnClients =
      CompositeArchRule.of(
              noClasses()
                  .that(not(CLIENT_CLASS))
                  .should()
                  .beMetaAnnotatedWith(CircuitBreaker.class))
          .and(
              noMethods()
                  .that()
                  .areDeclaredInClassesThat(not(CLIENT_CLASS))
                  .should()
                  .beMetaAnnotatedWith(CircuitBreaker.class))
          .allowEmptyShould(true)
          .because(
              "circuit breaker が外部システムの呼び出しだけを包むようにするため。"
                  + "Client の外に付けると、DB の失敗や業務の例外まで外部システムの不調として数える。"
                  + "直し方：@CircuitBreaker を、infrastructure.client の <System>Client のクラスかメソッドへ移す。"
                  + conventions(EXTERNAL_CLIENT_DOC, ADR_019));
}
