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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 日時とタイムゾーンの規約（.kiro/steering/datetime-timezone-conventions.md）をプロダクションコードへ静的に強制する。
 *
 * <p>Error Prone の {@code JavaTimeDefaultTimeZone} が既定タイムゾーン依存の {@code now()} をコンパイル時に弾くのを補完し、
 * ArchUnit ではレガシー日時型の遮断と、注入した {@code Clock} を迂回する時刻取得およびシステム {@code Clock} 生成の遮断を担う。
 *
 * <p>テストコードは対象外にする。テストは規約に従い {@code Clock.fixed(...)} と {@code Instant.parse(...)} を使う前提で、{@code
 * now()} の既定タイムゾーン依存は Error Prone が全 JavaCompile で検出する。
 */
class DateTimeConventionsArchTest {

  private static final String CLOCK_BEAN_METHOD = "com.example.demo.DemoApplication.clock()";

  // 引数に既存 Clock を受け取る tick(Clock, Duration) と offset(Clock, Duration) は、注入 Clock から派生できるため対象外にする。
  private static final Set<String> SYSTEM_CLOCK_FACTORY_METHODS =
      Set.of(
          "systemUTC", "systemDefaultZone", "system", "tickMillis", "tickSeconds", "tickMinutes");

  private static JavaClasses productionClasses;

  @BeforeAll
  static void importProductionClasses() {
    // 生成コード（jOOQ など）は規約の対象外。将来生成物が入っても誤検知しないよう場所で除外する。
    final ImportOption skipGenerated =
        location -> !location.contains("/jooq/") && !location.contains("/generated/");

    productionClasses =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(skipGenerated)
            .importPackages("com.example.demo");
  }

  /** レガシー日時型を用途に対応する {@code java.time} 型へ置き換える。 */
  @Test
  void legacyDateTimeTypesAreNotUsed() {
    final ArchRule rule =
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

    rule.check(productionClasses);
  }

  /** 現在時刻を使うクラスへ {@code Clock} をコンストラクタ注入し、引数なしの時刻取得を {@code now(clock)} へ置き換える。 */
  @Test
  void currentTimeIsObtainedThroughInjectedClock() {
    injectedClockRule().check(productionClasses);
  }

  /** システム {@code Clock} は {@code com.example.demo.DemoApplication.clock()} だけで生成する。 */
  @Test
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
    final int parameterCount = call.getTarget().getRawParameterTypes().size();

    if ("java.lang.System".equals(owner) && "currentTimeMillis".equals(method)) {
      return "違反したクラスに Clock をコンストラクタ注入し、System.currentTimeMillis() を clock.millis() に置き換える。";
    }
    if ("java.time.ZoneId".equals(owner) && "systemDefault".equals(method)) {
      return "ZoneId.systemDefault() を削除し、使用する ZoneId をコンストラクタ引数または設定値で受け取る。UTCなら ZoneOffset.UTC を使う。";
    }
    if (owner.startsWith("java.time.") && "now".equals(method) && parameterCount == 0) {
      return "違反したクラスに Clock をコンストラクタ注入し、" + owner + ".now() を " + owner + ".now(clock) に置き換える。";
    }
    if (Clock.class.getName().equals(owner)
        && SYSTEM_CLOCK_FACTORY_METHODS.contains(method)
        && !isDemoApplicationClockBeanMethod(call)) {
      final String signature = "Clock." + method + (parameterCount == 0 ? "()" : "(...)");
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
