package com.example.demo.architecture;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.example.demo.DemoApplication;
import com.example.demo.testkit.SharedTestConfiguration;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * バックエンドのテストコード規約（.kiro/steering/backend-testing-conventions.md）を静的に強制する。
 *
 * <p>コメントや Javadoc はバイトコードに残らないため ArchUnit では検査できない。テストの意図は、実行時に保持され レポートにも出る {@link DisplayName}
 * で明記させ、その存在をここで強制する。あわせて、可視性・命名・共有構成の {@code @Import}・禁止 API・レガシー日時型・{@code @Disabled}
 * の理由といった、規約のうち機械判定できる項目を担う。
 *
 * <p>解析対象は手書きのテストコードだけに {@link TestCodeOnly} で限定する。生成コードは対象外にする。
 */
@SuppressWarnings({"PMD.TestClassWithoutTestCases", "PMD.TooManyStaticImports"})
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = TestCodeOnly.class)
class TestConventionsArchTest {

  /** テスト規約の根拠パッケージ。レガシー日時ルールから除外する ArchUnit 定義自身の置き場所。 */
  private static final String ARCHITECTURE_PACKAGE = "com.example.demo.architecture..";

  /** すべての {@code @Test} メソッドに {@code @DisplayName} で検証意図を明記させる。 */
  @ArchTest
  /* package */ static final ArchRule testsMustDeclareDisplayName =
      methods()
          .that()
          .areAnnotatedWith(Test.class)
          .should()
          .beAnnotatedWith(DisplayName.class)
          .because("各テストの意図を @DisplayName で明示し、レポートから何を検証したか追えるようにする。")
          .allowEmptyShould(true);

  /** {@code @Test} メソッドはパッケージプライベートにする（JUnit 5 は public を要求しない）。 */
  @ArchTest
  /* package */ static final ArchRule testMethodsAreNotPublic =
      methods()
          .that()
          .areAnnotatedWith(Test.class)
          .should()
          .notBePublic()
          .because("テストメソッドに public を付けない。JUnit 5 は package-private を実行する。")
          .allowEmptyShould(true);

  /** {@code @Test} を持つクラスはパッケージプライベートにする。 */
  @ArchTest
  /* package */ static final ArchRule testClassesAreNotPublic =
      classes()
          .that()
          .containAnyMethodsThat(annotatedWith(Test.class))
          .should()
          .notBePublic()
          .because("テストクラスに public を付けない。JUnit 5 は package-private を実行する。")
          .allowEmptyShould(true);

  /** {@code @Test} を持つクラス名は {@code Test} で終わらせ、補助クラスと区別する。 */
  @ArchTest
  /* package */ static final ArchRule testClassesEndWithTest =
      classes()
          .that()
          .containAnyMethodsThat(annotatedWith(Test.class))
          .should()
          .haveSimpleNameEndingWith("Test")
          .because("テストクラス名は ...Test を接尾辞にし、拡張や合成アノテーションなど非テストと区別する。")
          .allowEmptyShould(true);

  /** {@code @SpringBootTest} を直接付けたクラスは共有構成を {@code @Import} してコンテキストキャッシュを効かせる。 */
  @ArchTest
  /* package */ static final ArchRule springBootTestsImportSharedConfiguration =
      classes()
          .that()
          .areAnnotatedWith(SpringBootTest.class)
          .should(importSharedTestConfiguration())
          .because(
              "フルの @SpringBootTest は "
                  + "SharedTestConfiguration を @Import して構成を揃え、テストが増えても"
                  + "コンテキストの再ロードを増やさない。共有差し替えが要る合成アノテーション自身もこれを内蔵する。")
          .allowEmptyShould(true);

  /** {@code assertTimeoutPreemptively} を禁止する。別スレッド実行で本番経路と文脈が変わるため。 */
  @ArchTest
  /* package */ static final ArchRule assertTimeoutPreemptivelyIsNotUsed =
      noClasses()
          .should()
          .callMethodWhere(target(name("assertTimeoutPreemptively")))
          .because(
              "assertTimeoutPreemptively は別スレッドで処理を実行し、トランザクション・"
                  + "セキュリティコンテキスト・ログのコンテキストが本番経路と変わり得るため既定にしない。"
                  + "期限付きの条件待機か、非プリエンプティブな assertTimeout を使う。");

  /** テストコードでもレガシー日時型を禁止する（型を参照する ArchUnit 定義自身がある architecture は除外）。 */
  @ArchTest
  /* package */ static final ArchRule legacyDateTimeTypesAreNotUsedInTests =
      noClasses()
          .that()
          .resideOutsideOfPackage(ARCHITECTURE_PACKAGE)
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
              "テストデータの絶対時刻には Instant、日付だけには LocalDate、時刻だけには LocalTime を使う。"
                  + "禁止型を参照して規約を強制する architecture パッケージ自身は対象外にする。")
          .allowEmptyShould(true);

  /** {@code @Disabled} は無言のスキップを避けるため理由を必須にする（メソッド）。 */
  @ArchTest
  /* package */ static final ArchRule disabledTestMethodsRequireReason =
      methods()
          .that()
          .areAnnotatedWith(Disabled.class)
          .should(declareDisabledReason())
          .because("@Disabled で止めるときは理由を書き、無言のスキップを残さない。")
          .allowEmptyShould(true);

  /** {@code @Disabled} は無言のスキップを避けるため理由を必須にする（クラス）。 */
  @ArchTest
  /* package */ static final ArchRule disabledTestClassesRequireReason =
      classes()
          .that()
          .areAnnotatedWith(Disabled.class)
          .should(declareDisabledReasonOnClass())
          .because("@Disabled で止めるときは理由を書き、無言のスキップを残さない。")
          .allowEmptyShould(true);

  private static ArchCondition<JavaClass> importSharedTestConfiguration() {
    return new ArchCondition<>("import " + SharedTestConfiguration.class.getSimpleName()) {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        final boolean imported =
            item.isAnnotatedWith(Import.class)
                && Arrays.asList(item.getAnnotationOfType(Import.class).value())
                    .contains(SharedTestConfiguration.class);
        if (!imported) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getFullName()
                      + " は @SpringBootTest を直接付けているが "
                      + "@Import(SharedTestConfiguration.class) を持たない。"
                      + "修正方法: 共有構成を @Import するか、それを内蔵する合成アノテーションを使う。"));
        }
      }
    };
  }

  private static ArchCondition<JavaMethod> declareDisabledReason() {
    return new ArchCondition<>("declare a @Disabled reason") {
      @Override
      public void check(final JavaMethod item, final ConditionEvents events) {
        if (item.getAnnotationOfType(Disabled.class).value().isBlank()) {
          events.add(
              SimpleConditionEvent.violated(
                  item, item.getFullName() + " の @Disabled に理由がない。value に停止理由を書く。"));
        }
      }
    };
  }

  private static ArchCondition<JavaClass> declareDisabledReasonOnClass() {
    return new ArchCondition<>("declare a @Disabled reason") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        if (item.getAnnotationOfType(Disabled.class).value().isBlank()) {
          events.add(
              SimpleConditionEvent.violated(
                  item, item.getFullName() + " の @Disabled に理由がない。value に停止理由を書く。"));
        }
      }
    };
  }
}
