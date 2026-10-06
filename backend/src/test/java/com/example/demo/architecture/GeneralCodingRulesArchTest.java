package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.properties.HasOwner;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;

/** ArchUnit が提供する汎用コーディング規則をプロダクションコードへ適用する。 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class GeneralCodingRulesArchTest {

  /** Java の実装規約。 */
  private static final String JAVA_CODING_DOC = "規約：docs/backend/java-coding.md";

  /** ロギングの規約と、ログの属性を決めた ADR のパス。 */
  private static final String LOGGING_DOCS =
      "規約：docs/backend/java-coding.md、docs/adr/ADR-015-structure-and-protect-observability-data.md";

  /** 日時の規約と ADR のパス。 */
  private static final String DATE_TIME_DOCS =
      "規約：docs/datetime/timezone-conventions.md、docs/adr/ADR-006-utc-instant-absolute-time-policy.md";

  /** 標準出力と標準エラー出力への直接アクセスを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule noClassesShouldAccessStandardStreams =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.because(
          "ログは logger を通して一行一 JSON の ECS で出し、同じ event を OpenTelemetry へ送るため。"
              + "直し方：System.out と System.err への出力を @Slf4j の log に置き換え、"
              + "例外は log.atError().setCause(exception) で logger に渡す。"
              + "規約：docs/observability/conventions.md、docs/backend/java-coding.md");

  /** {@link Exception} や {@link RuntimeException} のような汎用例外の送出を禁止する。 */
  @ArchTest
  /* package */ static final ArchRule noClassesShouldThrowGenericExceptions =
      GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.because(
          "呼び出し側と ApiExceptionHandler が例外の型で失敗を区別し、HTTP の応答に対応づけられるようにするため。"
              + "直し方：Throwable、Exception、RuntimeException、Error を投げない。"
              + "業務上の失敗は shared.failure の NotFoundException、BusinessRuleViolationException と "
              + "shared.concurrency の ConflictException を投げ、"
              + "プログラムの誤りは IllegalStateException や IllegalArgumentException を投げる。"
              + "規約：docs/backend/class-roles/business-exception.md、docs/backend/java-coding.md、"
              + "docs/adr/ADR-013-standardize-http-api-contracts.md");

  /** 見つからないことを {@link NoSuchElementException} で表すことを禁止する。 */
  // ponytail: 上限：Optional.get()、Iterator.next()、OptionalInt などの orElseThrow() と getAsInt()、
  // Optional::orElseThrow のメソッド参照のように、JDK の API が中で投げる NoSuchElementException は検出しない。
  // 漏れが見つかったら、その API の呼び出しを同じ規則に足す。
  @ArchTest
  /* package */ static final ArchRule noSuchElementExceptionIsNotThrown =
      noClasses()
          .should()
          .callConstructorWhere(
              JavaCall.Predicates.target(
                  HasOwner.Predicates.With.owner(
                      JavaClass.Predicates.assignableTo(NoSuchElementException.class))))
          .orShould()
          .callMethod(Optional.class, "orElseThrow")
          .because(
              "見つからないことを JDK の NoSuchElementException で表すと、ApiExceptionHandler が 404 にできず 500 になるため。"
                  + "直し方：見つからない集約や行には shared.failure の NotFoundException を投げ、"
                  + "Optional は orElseThrow(() -> new NotFoundException(\"order not found: orderId=...\")) で取り出す。"
                  + "プログラムの誤りは IllegalStateException にする。"
                  + "規約：docs/backend/class-roles/business-exception.md、"
                  + "docs/adr/ADR-061-map-business-exceptions-to-404-409-422.md");

  /** {@code java.util.logging} の使用を禁止し、アプリケーションのロギング実装を統一する。 */
  @ArchTest
  /* package */ static final ArchRule noClassesShouldUseJavaUtilLogging =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.because(
          "ログの書き方を一つにし、SLF4J の key-value を OpenTelemetry へ送るログの属性にするため。"
              + "直し方：java.util.logging を使わず、クラスに @Slf4j を付けて log で記録する。"
              + LOGGING_DOCS);

  /** 機能コードからロギング実装 API への直接依存を禁止し、SLF4J facade を使用する。 */
  @ArchTest
  /* package */ static final ArchRule featureCodeUsesOnlySlf4jFacade =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.demo")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "ch.qos.logback..",
              "org.apache.logging.log4j..",
              "org.apache.log4j..",
              "org.apache.commons.logging..")
          .allowEmptyShould(true)
          .because(
              "ロギングの実装を差し替えても、機能コードを変えずに済むようにするため。"
                  + "直し方：Logback、Log4j、Apache Commons Logging の API を使わず、@Slf4j の SLF4J の log で記録する。"
                  + "実装 API との接続はベースパッケージ直下の設定クラスに置く。"
                  + JAVA_CODING_DOC);

  /** Joda-Time の使用を禁止し、日時 API を {@code java.time} に統一する。 */
  @ArchTest
  /* package */ static final ArchRule noClassesShouldUseJodaTime =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME.because(
          "絶対時刻を Instant に統一し、保存、API、ログで時刻の解釈を一つにするため。"
              + "直し方：Joda-Time の型を、絶対時刻は Instant、日付だけは LocalDate、時刻だけは LocalTime に置き換える。"
              + DATE_TIME_DOCS);

  /** フィールドインジェクションを禁止し、依存をコンストラクタで明示する。 */
  @ArchTest
  /* package */ static final ArchRule noClassesShouldUseFieldInjection =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.because(
          "依存をコンストラクタに明示して final にし、テストで Spring なしに組み立てられるようにするため。"
              + "直し方：フィールドの @Autowired、@Value などの注入アノテーションを外し、"
              + "依存を private final フィールドにしてコンストラクタ引数で受け取る。"
              + JAVA_CODING_DOC);

  /** {@code @Autowired} を通常メソッドへ付けるメソッドインジェクションを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule autowiredMethodsAreNotUsed =
      noMethods()
          .should()
          .beAnnotatedWith(Autowired.class)
          .because(
              "依存をコンストラクタに明示して final にし、テストで Spring なしに組み立てられるようにするため。"
                  + "直し方：メソッドの @Autowired を外し、依存をコンストラクタ引数で受け取る。"
                  + "コンストラクタが一つなら @Autowired を付けない。"
                  + JAVA_CODING_DOC);

  /** Java の {@code assert} 文に失敗理由のメッセージを必須とする。 */
  @ArchTest
  /* package */ static final ArchRule assertionsShouldHaveDetailMessage =
      GeneralCodingRules.ASSERTIONS_SHOULD_HAVE_DETAIL_MESSAGE.because(
          "失敗のログだけで原因と値を特定できるようにするため。"
              + "直し方：assert 文を「assert 条件 : \"対象と値\";」の形にし、失敗の詳細を書く。"
              + JAVA_CODING_DOC);

  /** 非推奨 API の新規利用を禁止する。 */
  @ArchTest
  /* package */ static final ArchRule deprecatedApiShouldNotBeUsed =
      GeneralCodingRules.DEPRECATED_API_SHOULD_NOT_BE_USED.because(
          "依存ライブラリの更新で API が削除されても、ビルドが壊れないようにするため。"
              + "直し方：@Deprecated の API を、その Javadoc が示す代わりの API に置き換える。"
              + "規約：docs/backend/java-coding.md、docs/adr/ADR-021-group-dependabot-minor-and-patch-updates.md");

  /** {@code java.util.Date} などの旧日時 API を禁止する。 */
  @ArchTest
  /* package */ static final ArchRule oldDateAndTimeClassesShouldNotBeUsed =
      GeneralCodingRules.OLD_DATE_AND_TIME_CLASSES_SHOULD_NOT_BE_USED.because(
          "絶対時刻を Instant に統一し、保存、API、ログで時刻の解釈を一つにするため。"
              + "直し方：java.util.Date、Calendar、java.sql.Timestamp などを、"
              + "絶対時刻は Instant、日付だけは LocalDate、時刻だけは LocalTime に置き換える。"
              + DATE_TIME_DOCS);
}
