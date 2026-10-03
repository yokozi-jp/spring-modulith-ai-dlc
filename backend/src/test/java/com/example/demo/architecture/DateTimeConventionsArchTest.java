package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 日時とタイムゾーンの規約（docs/datetime/timezone-conventions.md）をプロダクションコードへ静的に強制する。
 *
 * <p>Error Prone の {@code JavaTimeDefaultTimeZone} が既定タイムゾーン依存の {@code now()} をコンパイル時に弾くのを補完し、
 * ArchUnit ではレガシー日時型の遮断と、注入した {@code Clock} を迂回する時刻取得およびシステム {@code Clock} 生成の遮断、 {@code Instant}
 * 以外の {@code now(Clock)}（UTC の {@code Clock} のゾーンに頼る値）の遮断を担う。
 *
 * <p>ArchUnit の JUnit 5 連携（{@link AnalyzeClasses} と {@link ArchTest}）を使う。解析対象のクラスは
 * {@code @AnalyzeClasses} が import・キャッシュし、{@code @ArchTest} フィールドの各ルールを自動で評価する。テストコードと生成コードは {@link
 * ProductionCodeOnly} で対象外にする。テストは規約に従い {@code Clock.fixed(...)} と {@code Instant.parse(...)}
 * を使う前提で、{@code now()} の既定タイムゾーン依存は Error Prone が全 JavaCompile で検出する。
 */
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class DateTimeConventionsArchTest {

  /** 唯一システム {@code Clock} の生成を許す {@code @Bean} メソッドの完全修飾名。 */
  private static final String CLOCK_BEAN_METHOD = "com.example.demo.DemoApplication.clock()";

  /**
   * 生成を禁止するシステム {@code Clock} ファクトリのメソッド名。{@code InstantSource.system()} も同じく禁止する。
   *
   * <p>引数に既存 {@code Clock} を受け取る {@code tick(Clock, Duration)} と {@code offset(Clock, Duration)}
   * は、注入 {@code Clock} から派生できるため対象外にする。
   */
  private static final Set<String> SYSTEM_CLOCK_FACTORY_METHODS =
      Set.of(
          "systemUTC", "systemDefaultZone", "system", "tickMillis", "tickSeconds", "tickMinutes");

  /**
   * システム {@code Clock} ファクトリの違反に付く修正方法の文。
   *
   * <p>{@code because(...)} の共通文は {@code Clock.systemUTC()} などを含むため、拒否テストは違反ごとの修正方法の文で検証する。
   */
  private static final String FACTORY_REMEDIATION = " は " + CLOCK_BEAN_METHOD + " 以外では使えない";

  /** {@code Clock} を受け取らない {@code now(...)} の違反に付く修正方法の文。 */
  private static final String NOW_REMEDIATION = " を Instant.now(clock) に置き換える";

  /** {@code Instant} 以外の {@code now(Clock)} の違反に付く修正方法の文。 */
  private static final String NOW_CLOCK_REMEDIATION = ".now(Clock) は Clock のゾーンで値を決め";

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

  /** 現在時刻を使うクラスへ {@code Clock} をコンストラクタ注入し、現在時刻を {@code Instant.now(clock)} で取る。 */
  @ArchTest
  /* package */ static final ArchRule currentTimeIsObtainedThroughInjectedClock =
      injectedClockRule();

  /** システム {@code Clock} は {@code com.example.demo.DemoApplication.clock()} だけで生成する。 */
  @Test
  @DisplayName("システム Clock 生成を DemoApplication.clock() 以外で拒否する")
  // JavaClasses は ArchUnit の import 結果を表す公開 API 型であり、インタフェースへ置き換えられない。
  @SuppressWarnings("PMD.LooseCoupling")
  void systemClockFactoriesAreRejectedOutsideDemoApplicationClockBeanMethod() {
    // フィクスチャを実際に呼び出してIDEにも使用済みと認識させる。拒否判定は続くArchUnitの検査が担う。
    assertThat(SystemClockFactoryBypass.createForbiddenClocks()).hasSize(7);

    final JavaClasses bypassClass =
        new ClassFileImporter().importClasses(SystemClockFactoryBypass.class);

    assertThatThrownBy(() -> injectedClockRule().check(bypassClass))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("InstantSource.system()" + FACTORY_REMEDIATION)
        .hasMessageContaining("Clock.systemUTC()" + FACTORY_REMEDIATION)
        .hasMessageContaining("Clock.systemDefaultZone()" + FACTORY_REMEDIATION)
        .hasMessageContaining("Clock.system(...)" + FACTORY_REMEDIATION)
        .hasMessageContaining("Clock.tickMillis(...)" + FACTORY_REMEDIATION)
        .hasMessageContaining("Clock.tickSeconds(...)" + FACTORY_REMEDIATION)
        .hasMessageContaining("Clock.tickMinutes(...)" + FACTORY_REMEDIATION);
  }

  /** {@code Clock} を受け取らない {@code now(...)} と、{@code Instant} 以外の {@code now(Clock)} を拒否する。 */
  @Test
  @DisplayName("Clock を受け取らない now(...) と Instant 以外の now(Clock) を拒否する")
  // JavaClasses は ArchUnit の import 結果を表す公開 API 型であり、インタフェースへ置き換えられない。
  @SuppressWarnings("PMD.LooseCoupling")
  void nowWithoutInjectedClockAndZoneDependentNowClockAreRejected() {
    // フィクスチャを実際に呼び出してIDEにも使用済みと認識させる。拒否判定は続くArchUnitの検査が担う。
    assertThat(
            CurrentTimeBypass.createForbiddenValues(
                Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC)))
        .hasSize(12);

    final JavaClasses bypassClass = new ClassFileImporter().importClasses(CurrentTimeBypass.class);

    assertThatThrownBy(() -> injectedClockRule().check(bypassClass))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("java.time.Instant.now()" + NOW_REMEDIATION)
        .hasMessageContaining("java.time.LocalDate.now(ZoneId)" + NOW_REMEDIATION)
        .hasMessageContaining("java.time.OffsetDateTime.now(ZoneId)" + NOW_REMEDIATION)
        .hasMessageContaining("java.time.LocalDate" + NOW_CLOCK_REMEDIATION)
        .hasMessageContaining("java.time.LocalDateTime" + NOW_CLOCK_REMEDIATION)
        .hasMessageContaining("java.time.LocalTime" + NOW_CLOCK_REMEDIATION)
        .hasMessageContaining("java.time.ZonedDateTime" + NOW_CLOCK_REMEDIATION)
        .hasMessageContaining("java.time.OffsetDateTime" + NOW_CLOCK_REMEDIATION)
        .hasMessageContaining("java.time.OffsetTime" + NOW_CLOCK_REMEDIATION)
        .hasMessageContaining("java.time.Year" + NOW_CLOCK_REMEDIATION)
        .hasMessageContaining("java.time.YearMonth" + NOW_CLOCK_REMEDIATION)
        .hasMessageContaining("java.time.MonthDay" + NOW_CLOCK_REMEDIATION);
  }

  /** メソッド参照による迂回も呼び出しと同じく拒否する。 */
  @Test
  @DisplayName("Instant::now などのメソッド参照による迂回を拒否する")
  void methodReferencesBypassingInjectedClockAreRejected() {
    // フィクスチャを実際に呼び出してIDEにも使用済みと認識させる。拒否判定は続くArchUnitの検査が担う。
    assertThat(MethodReferenceBypass.createForbiddenReferences()).hasSize(4);

    assertThatThrownBy(
            () ->
                injectedClockRule()
                    .check(new ClassFileImporter().importClasses(MethodReferenceBypass.class)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("java.time.Instant.now()" + NOW_REMEDIATION)
        .hasMessageContaining("java.time.LocalDate" + NOW_CLOCK_REMEDIATION)
        .hasMessageContaining("Clock.systemUTC()" + FACTORY_REMEDIATION)
        .hasMessageContaining("InstantSource.system()" + FACTORY_REMEDIATION);
  }

  /** 述語が広すぎて許可経路まで拒否しないことを確かめる。 */
  @Test
  @DisplayName("Instant.now(clock) と ofInstant による日付の導出を許可する")
  // JavaClasses は ArchUnit の import 結果を表す公開 API 型であり、インタフェースへ置き換えられない。
  @SuppressWarnings("PMD.LooseCoupling")
  void instantNowClockAndOfInstantAreAllowed() {
    assertThat(
            InjectedClockUsage.useInjectedClock(
                Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC),
                ZoneId.of("Asia/Tokyo")))
        .hasSize(3);

    final JavaClasses usageClass = new ClassFileImporter().importClasses(InjectedClockUsage.class);

    // 違反があれば check が AssertionError を投げ、テストが失敗する。
    injectedClockRule().check(usageClass);
  }

  private static ArchRule injectedClockRule() {
    return classes()
        .should(obtainTimeOnlyThroughInjectedClock())
        .because(
            "現在時刻を使うクラスは Clock をコンストラクタ引数で受け取り、"
                + "Instant.now(clock) または clock.millis() を使う。"
                + "システム Clock を生成できるのは "
                + CLOCK_BEAN_METHOD
                + " だけであり、このメソッドが Clock.systemUTC() をマイクロ秒単位の tick で包んで返す。"
                + "地域の日付や時刻は ZoneId.systemDefault() や Clock のゾーンに頼らず、"
                + "LocalDate.ofInstant(Instant.now(clock), zone) のように設定値の ZoneId で求める。");
  }

  private static ArchCondition<JavaClass> obtainTimeOnlyThroughInjectedClock() {
    return new ArchCondition<>("obtain current time only through an injected Clock") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        // メソッド参照（Instant::now など）も迂回経路になるため、呼び出しと参照の両方を調べる。
        // コンストラクタは名前が <init> で、どの判定にも一致しない。
        for (final JavaCodeUnitAccess<?> call : item.getCodeUnitAccessesFromSelf()) {
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

  private static String remediationForForbiddenCall(final JavaCodeUnitAccess<?> call) {
    final String owner = call.getTargetOwner().getFullName();
    final String method = call.getTarget().getName();

    if ("java.lang.System".equals(owner) && "currentTimeMillis".equals(method)) {
      return "違反したクラスに Clock をコンストラクタ注入し、System.currentTimeMillis() を clock.millis() に置き換える。";
    }
    if ("java.time.ZoneId".equals(owner) && "systemDefault".equals(method)) {
      return "ZoneId.systemDefault() を削除し、使用する ZoneId をコンストラクタ引数または設定値で受け取る。UTCなら ZoneOffset.UTC を使う。";
    }

    final String nowRemediation = remediationForNow(call, owner, method);
    if (nowRemediation != null) {
      return nowRemediation;
    }
    return remediationForSystemClockFactory(call, owner, method);
  }

  /**
   * 注入 {@code Clock} を迂回する {@code now()} と {@code now(ZoneId)}、および {@code Instant} 以外の {@code
   * now(Clock)} に対する修正方法を返す。該当しなければ {@code null}。
   *
   * <p>{@code now(ZoneOffset)} はコンパイラが {@code now(ZoneId)} へ束縛するため、呼び出し先の引数型 {@code ZoneId} で判定できる。
   */
  private static String remediationForNow(
      final JavaCodeUnitAccess<?> call, final String owner, final String method) {
    if (!owner.startsWith("java.time.") || !"now".equals(method)) {
      return null;
    }
    final List<JavaClass> params = call.getTarget().getRawParameterTypes();
    if (params.isEmpty() || isSoleParameter(params, ZoneId.class)) {
      final String signature = owner + (params.isEmpty() ? ".now()" : ".now(ZoneId)");
      return "違反したクラスに Clock をコンストラクタ注入し、"
          + signature
          + " を Instant.now(clock) に置き換える。"
          + "地域の日付や時刻が必要なら LocalDate.ofInstant(Instant.now(clock), zone) のように設定値の ZoneId を明示する。";
    }
    if (isSoleParameter(params, Clock.class) && !Instant.class.getName().equals(owner)) {
      return owner
          + ".now(Clock) は Clock のゾーンで値を決め、注入 Clock は UTC である。"
          + "Instant.now(clock) を取り、LocalDate.ofInstant(Instant.now(clock), zone) のように設定値の ZoneId を明示して求める。";
    }
    return null;
  }

  private static boolean isSoleParameter(final List<JavaClass> params, final Class<?> type) {
    return params.size() == 1 && params.get(0).isEquivalentTo(type);
  }

  /**
   * {@code DemoApplication.clock()} 以外でのシステム {@code Clock} ファクトリと {@code InstantSource.system()}
   * の呼び出しに対する修正方法を返す。該当しなければ {@code null}。
   */
  private static String remediationForSystemClockFactory(
      final JavaCodeUnitAccess<?> call, final String owner, final String method) {
    final boolean systemFactory =
        (Clock.class.getName().equals(owner) && SYSTEM_CLOCK_FACTORY_METHODS.contains(method))
            || (InstantSource.class.getName().equals(owner) && "system".equals(method));
    if (systemFactory && !isDemoApplicationClockBeanMethod(call)) {
      final boolean noArgs = call.getTarget().getRawParameterTypes().isEmpty();
      final String signature =
          call.getTargetOwner().getSimpleName() + "." + method + (noArgs ? "()" : "(...)");
      return signature
          + " は "
          + CLOCK_BEAN_METHOD
          + " 以外では使えない。違反したクラスに Clock をコンストラクタ引数で受け取り、生成済みの clock を使う。";
    }
    return null;
  }

  private static boolean isDemoApplicationClockBeanMethod(final JavaCodeUnitAccess<?> call) {
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

    private static List<InstantSource> createForbiddenClocks() {
      return List.of(
          // Clock ではなく InstantSource を返すが、注入 Clock を介さずシステム時刻を読むため禁止する。
          InstantSource.system(),
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

  /** ArchUnit の拒否経路を検証するため、注入 Clock を迂回する now と UTC の Clock に頼る now(Clock) を意図的に含めたフィクスチャ。 */
  @SuppressWarnings("JavaTimeDefaultTimeZone")
  private static final class CurrentTimeBypass {

    private static List<TemporalAccessor> createForbiddenValues(final Clock clock) {
      return List.of(
          // 注入 Clock を介さずシステム時刻を読むため禁止する。
          Instant.now(),
          // ZoneIdを明示しても、注入 Clock を介さずシステム時刻を読むため禁止する。
          LocalDate.now(ZoneId.of("Asia/Tokyo")),
          // ZoneOffsetはZoneIdの派生型であり、now(ZoneId)と同じく注入 Clock を迂回するため禁止する。
          OffsetDateTime.now(ZoneOffset.UTC),
          // 以下は注入 Clock（UTC）のゾーンで値を決め、業務のゾーンと食い違うため禁止する。
          LocalDate.now(clock),
          // UTCの日時になり、日本では9時間ずれる。
          LocalDateTime.now(clock),
          // UTCの時刻になる。
          LocalTime.now(clock),
          // ゾーンがUTCに固定される。
          ZonedDateTime.now(clock),
          // オフセットがUTCに固定される。
          OffsetDateTime.now(clock),
          // オフセットがUTCに固定される。
          OffsetTime.now(clock),
          // 年の境目でUTCの年になる。
          Year.now(clock),
          // 月の境目でUTCの年月になる。
          YearMonth.now(clock),
          // 日の境目でUTCの月日になる。
          MonthDay.now(clock));
    }
  }

  /** ArchUnit の拒否経路を検証するため、禁止対象をメソッド参照で意図的に含めたフィクスチャ。 */
  private static final class MethodReferenceBypass {

    private static List<Object> createForbiddenReferences() {
      // 呼び出しではなく参照なので、ArchUnit では JavaMethodReference として現れる。
      final Supplier<Instant> now = Instant::now;
      final Function<Clock, LocalDate> today = LocalDate::now;
      final Supplier<Clock> clock = Clock::systemUTC;
      final Supplier<InstantSource> source = InstantSource::system;
      return List.of(now, today, clock, source);
    }
  }

  /** 注入 Clock から絶対時刻を取り、設定値の ZoneId で地域の日付を求める許可経路のフィクスチャ。 */
  private static final class InjectedClockUsage {

    private static List<Object> useInjectedClock(final Clock clock, final ZoneId zone) {
      return List.of(
          Instant.now(clock), LocalDate.ofInstant(Instant.now(clock), zone), clock.millis());
    }
  }
}
