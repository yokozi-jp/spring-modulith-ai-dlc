package com.example.demo;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * 日時とタイムゾーンの規約（.kiro/steering/datetime-timezone-conventions.md）をプロダクションコードへ静的に強制する。
 *
 * <p>Error Prone の {@code JavaTimeDefaultTimeZone} が既定タイムゾーン依存の {@code now()} をコンパイル時に弾くのを補完し、
 * ArchUnit ではレガシー日時型の遮断と、注入した {@code Clock} を迂回する時刻取得およびシステム {@code Clock} 生成の遮断を担う。
 *
 * <p>ArchUnit の JUnit 5 連携（{@link AnalyzeClasses} と {@link ArchTest}）を使う。解析対象のクラスは
 * {@code @AnalyzeClasses} が import・キャッシュし、{@code @ArchTest} フィールドの各ルールを自動で評価する。テストコードと生成コードは {@link
 * ProductionCodeOnly} で対象外にする。テストは規約に従い {@code Clock.fixed(...)} と {@code Instant.parse(...)}
 * を使う前提で、{@code now()} の既定タイムゾーン依存は Error Prone が全 JavaCompile で検出する。
 */
@AnalyzeClasses(
    packages = "com.example.demo",
    importOptions = DateTimeConventionsArchTest.ProductionCodeOnly.class)
class DateTimeConventionsArchTest {

  /** 唯一システム {@code Clock} の生成を許す {@code @Bean} メソッドの完全修飾名。 */
  private static final String CLOCK_BEAN_METHOD = "com.example.demo.DemoApplication.clock()";

  /**
   * 生成を禁止するシステム {@code Clock} ファクトリのメソッド名。
   *
   * <p>引数に既存 {@code Clock} を受け取る {@code tick(Clock, Duration)} と {@code offset(Clock, Duration)}
   * は、注入 {@code Clock} から派生できるため対象外にする。
   */
  private static final Set<String> SYSTEM_CLOCK_FACTORY_METHODS =
      Set.of(
          "systemUTC", "systemDefaultZone", "system", "tickMillis", "tickSeconds", "tickMinutes");

  /** レガシー日時型を用途に対応する {@code java.time} 型へ置き換える。 */
  @ArchTest
  /* package */ static final ArchRule legacyDateTimeTypesAreNotUsed =
      noClasses()
          .should()
          .dependOnClassesThat()
          .belongToAnyOf(
              java.util.Date.class,
              java.sql.Date.class,
              java.sql.Time.class,
              java.sql.Timestamp.class,
              Calendar.class,
              GregorianCalendar.class,
              TimeZone.class,
              java.text.DateFormat.class,
              java.text.SimpleDateFormat.class)
          .because(
              "違反した型を用途に応じて置き換える。"
                  + "絶対時刻には Instant、日付だけの値には LocalDate、時刻だけの値には LocalTime、"
                  + "日時の書式化には DateTimeFormatter を使う。");

  /** 現在時刻を使うクラスへ {@code Clock} をコンストラクタ注入し、引数なしの時刻取得を {@code now(clock)} へ置き換える。 */
  @ArchTest
  /* package */ static final ArchRule currentTimeIsObtainedThroughInjectedClock =
      injectedClockRule();

  /** システム {@code Clock} は {@code com.example.demo.DemoApplication.clock()} だけで生成する。 */
  @Test
  // JavaClasses は ArchUnit の import 結果を表す公開 API 型であり、インタフェースへ置き換えられない。
  @SuppressWarnings("PMD.LooseCoupling")
  void systemClockFactoriesAreRejectedOutsideDemoApplicationClockBeanMethod() {
    // フィクスチャを実際に呼び出してIDEにも使用済みと認識させる。拒否判定は続くArchUnitの検査が担う。
    assertThat(SystemClockFactoryBypass.createForbiddenClocks()).hasSize(6);

    final JavaClasses bypassClass =
        new ClassFileImporter().importClasses(SystemClockFactoryBypass.class);

    assertThatThrownBy(() -> injectedClockRule().check(bypassClass))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Clock.systemUTC()")
        .hasMessageContaining("Clock.systemDefaultZone()")
        .hasMessageContaining("Clock.system(...)")
        .hasMessageContaining("Clock.tickMillis(...)")
        .hasMessageContaining("Clock.tickSeconds(...)")
        .hasMessageContaining("Clock.tickMinutes(...)")
        .hasMessageContaining(CLOCK_BEAN_METHOD)
        .hasMessageContaining("Clock をコンストラクタ引数で受け取り");
  }

  private static ArchRule injectedClockRule() {
    return classes()
        .should(obtainTimeOnlyThroughInjectedClock())
        .because(
            "現在時刻を使うクラスは Clock をコンストラクタ引数で受け取り、"
                + "java.time 型の now(clock) または clock.millis() を使う。"
                + "システム Clock を生成できるのは "
                + CLOCK_BEAN_METHOD
                + " だけであり、このメソッドが Clock.systemUTC() を返す。"
                + "地域タイムゾーンが必要な処理は ZoneId.systemDefault() を使わず、ZoneId を引数または設定値で明示する。");
  }

  private static ArchCondition<JavaClass> obtainTimeOnlyThroughInjectedClock() {
    return new ArchCondition<>("obtain current time only through an injected Clock") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        for (final JavaMethodCall call : item.getMethodCallsFromSelf()) {
          final String remediation = remediationForForbiddenCall(call);
          if (remediation != null) {
            events.add(
                SimpleConditionEvent.violated(
                    item, call.getDescription() + "。修正方法: " + remediation));
          }
        }
      }
    };
  }

  private static String remediationForForbiddenCall(final JavaMethodCall call) {
    final String owner = call.getTargetOwner().getFullName();
    final String method = call.getTarget().getName();

    if ("java.lang.System".equals(owner) && "currentTimeMillis".equals(method)) {
      return "違反したクラスに Clock をコンストラクタ注入し、System.currentTimeMillis() を clock.millis() に置き換える。";
    }
    if ("java.time.ZoneId".equals(owner) && "systemDefault".equals(method)) {
      return "ZoneId.systemDefault() を削除し、使用する ZoneId をコンストラクタ引数または設定値で受け取る。UTCなら ZoneOffset.UTC を使う。";
    }

    final String noArgNowRemediation = remediationForNoArgNow(call, owner, method);
    if (noArgNowRemediation != null) {
      return noArgNowRemediation;
    }
    return remediationForSystemClockFactory(call, owner, method);
  }

  /** 引数なしの {@code java.time} 型 {@code now()}（既定タイムゾーン依存）に対する修正方法を返す。該当しなければ {@code null}。 */
  private static String remediationForNoArgNow(
      final JavaMethodCall call, final String owner, final String method) {
    if (owner.startsWith("java.time.")
        && "now".equals(method)
        && call.getTarget().getRawParameterTypes().isEmpty()) {
      return "違反したクラスに Clock をコンストラクタ注入し、" + owner + ".now() を " + owner + ".now(clock) に置き換える。";
    }
    return null;
  }

  /**
   * {@code DemoApplication.clock()} 以外でのシステム {@code Clock} ファクトリ呼び出しに対する修正方法を返す。該当しなければ {@code
   * null}。
   */
  private static String remediationForSystemClockFactory(
      final JavaMethodCall call, final String owner, final String method) {
    if (Clock.class.getName().equals(owner)
        && SYSTEM_CLOCK_FACTORY_METHODS.contains(method)
        && !isDemoApplicationClockBeanMethod(call)) {
      final boolean noArgs = call.getTarget().getRawParameterTypes().isEmpty();
      final String signature = "Clock." + method + (noArgs ? "()" : "(...)");
      return signature
          + " は "
          + CLOCK_BEAN_METHOD
          + " 以外では使えない。違反したクラスに Clock をコンストラクタ引数で受け取り、生成済みの clock を使う。";
    }
    return null;
  }

  private static boolean isDemoApplicationClockBeanMethod(final JavaMethodCall call) {
    // 唯一の許可点は @Bean DemoApplication.clock() から Clock.systemUTC() を呼ぶ場合だけとする。
    return DemoApplication.class.getName().equals(call.getOriginOwner().getFullName())
        && "clock".equals(call.getOrigin().getName())
        && call.getOrigin().getRawParameterTypes().isEmpty()
        && Clock.class.getName().equals(call.getTargetOwner().getFullName())
        && "systemUTC".equals(call.getTarget().getName())
        && call.getTarget().getRawParameterTypes().isEmpty();
  }

  /**
   * プロダクションコードだけを解析対象にする {@link ImportOption}。テストと生成コード（jOOQ など）は規約の対象外にする。
   *
   * <p>{@code @AnalyzeClasses} は {@link ImportOption} の型を受け取り、引数なしコンストラクタで生成する。将来生成物が入っても誤検知しないよう
   * 場所（ロケーション）で除外する。
   */
  /* package */ static final class ProductionCodeOnly implements ImportOption {

    /** テストのロケーションを除外する ArchUnit 標準の {@link ImportOption}。 */
    private static final ImportOption DO_NOT_INCLUDE_TESTS = new ImportOption.DoNotIncludeTests();

    @Override
    public boolean includes(final Location location) {
      return DO_NOT_INCLUDE_TESTS.includes(location)
          && !location.contains("/jooq/")
          && !location.contains("/generated/");
    }
  }

  /** ArchUnit の拒否経路を検証するため、禁止対象の呼び出しを意図的に含めたフィクスチャ。 */
  @SuppressWarnings("JavaTimeDefaultTimeZone")
  private static final class SystemClockFactoryBypass {

    private static List<Clock> createForbiddenClocks() {
      return List.of(
          // UTC指定でも、DemoApplication.clock()を介さずシステム Clock を直接生成するため禁止する。
          Clock.systemUTC(),
          // JVMの既定タイムゾーンとシステム時刻へ直接依存するため禁止する。
          Clock.systemDefaultZone(),
          // ZoneIdを明示しても、注入 Clock を介さずシステム時刻を取得するため禁止する。
          Clock.system(ZoneOffset.UTC),
          // ZoneIdだけを受け取るtickファクトリは、内部でシステム Clock を生成するため禁止する。
          Clock.tickMillis(ZoneOffset.UTC),
          // 秒単位のtickファクトリも、注入 Clock から派生させられないため禁止する。
          Clock.tickSeconds(ZoneOffset.UTC),
          // 分単位のtickファクトリも、注入 Clock から派生させられないため禁止する。
          Clock.tickMinutes(ZoneOffset.UTC));
    }
  }
}
