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
 * バックエンドのテストコード規約（docs/backend/testing-code-style.md）を静的に強制する。
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

  /** テストコードの規約のパス。 */
  private static final String TESTING_CODE_STYLE_DOC = "docs/backend/testing-code-style.md";

  /** {@code @Disabled} の理由の規則の直し方と規約。 */
  private static final String DISABLED_REASON_FIX =
      "直し方：@Disabled(\"<停止理由>\") のように value に停止理由を書く。規約：" + TESTING_CODE_STYLE_DOC;

  /** すべての {@code @Test} メソッドに {@code @DisplayName} で検証意図を明記させる。 */
  @ArchTest
  /* package */ static final ArchRule testsMustDeclareDisplayName =
      methods()
          .that()
          .areAnnotatedWith(Test.class)
          .should()
          .beAnnotatedWith(DisplayName.class)
          .because(
              "テストの検証意図と失敗対象をレポートから分かるようにするため。"
                  + "直し方：@Test メソッドに @DisplayName(\"<検証する振る舞い>\") を付ける。"
                  + "規約："
                  + TESTING_CODE_STYLE_DOC)
          .allowEmptyShould(true);

  /** {@code @Test} メソッドはパッケージプライベートにする（JUnit 5 は public を要求しない）。 */
  @ArchTest
  /* package */ static final ArchRule testMethodsAreNotPublic =
      methods()
          .that()
          .areAnnotatedWith(Test.class)
          .should()
          .notBePublic()
          .because("直し方：@Test メソッドの public を外し、パッケージプライベートにする。規約：" + TESTING_CODE_STYLE_DOC)
          .allowEmptyShould(true);

  /** {@code @Test} を持つクラスはパッケージプライベートにする。 */
  @ArchTest
  /* package */ static final ArchRule testClassesAreNotPublic =
      classes()
          .that()
          .containAnyMethodsThat(annotatedWith(Test.class))
          .should()
          .notBePublic()
          .because("直し方：テストクラスの public を外し、パッケージプライベートにする。規約：" + TESTING_CODE_STYLE_DOC)
          .allowEmptyShould(true);

  /** {@code @Test} を持つクラス名は {@code Test} で終わらせ、補助クラスと区別する。 */
  @ArchTest
  /* package */ static final ArchRule testClassesEndWithTest =
      classes()
          .that()
          .containAnyMethodsThat(annotatedWith(Test.class))
          .should()
          .haveSimpleNameEndingWith("Test")
          .because("直し方：@Test を持つクラスの名前を単数形の <対象>Test に変える。規約：" + TESTING_CODE_STYLE_DOC)
          .allowEmptyShould(true);

  /** {@code @SpringBootTest} を直接付けたクラスは共有構成を {@code @Import} してコンテキストキャッシュを効かせる。 */
  @ArchTest
  /* package */ static final ArchRule springBootTestsImportSharedConfiguration =
      classes()
          .that()
          .areAnnotatedWith(SpringBootTest.class)
          .should(importSharedTestConfiguration())
          .because(
              "フルの @SpringBootTest の構成を揃え、共有する Spring コンテキストを増やさないため。"
                  + "直し方：@Import(SharedTestConfiguration.class) を付けるか、それを内蔵する合成アノテーションを使い、"
                  + "テストごとの @Import や @MockBean で構成を分岐させない。"
                  + "規約："
                  + TESTING_CODE_STYLE_DOC)
          .allowEmptyShould(true);

  /** {@code assertTimeoutPreemptively} を禁止する。別スレッド実行で本番経路と文脈が変わるため。 */
  @ArchTest
  /* package */ static final ArchRule assertTimeoutPreemptivelyIsNotUsed =
      noClasses()
          .should()
          .callMethodWhere(target(name("assertTimeoutPreemptively")))
          .because(
              "直し方：イベントは Spring Modulith の Scenario で、それ以外は対象 API の期限付き条件待機か、"
                  + "非プリエンプティブな assertTimeout で待つ。"
                  + "規約："
                  + TESTING_CODE_STYLE_DOC);

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
              "絶対時刻を Instant に統一し、保存、API、ログで時刻の解釈を一つにするため。"
                  + "直し方：テストデータの絶対時刻には Instant、日付だけには LocalDate、時刻だけには LocalTime を使う。"
                  + "規約：docs/datetime/timezone-conventions.md、docs/adr/ADR-006-utc-instant-absolute-time-policy.md")
          .allowEmptyShould(true);

  /** {@code @Disabled} は無言のスキップを避けるため理由を必須にする（メソッド）。 */
  @ArchTest
  /* package */ static final ArchRule disabledTestMethodsRequireReason =
      methods()
          .that()
          .areAnnotatedWith(Disabled.class)
          .should(declareDisabledReason())
          .because(DISABLED_REASON_FIX)
          .allowEmptyShould(true);

  /** {@code @Disabled} は無言のスキップを避けるため理由を必須にする（クラス）。 */
  @ArchTest
  /* package */ static final ArchRule disabledTestClassesRequireReason =
      classes()
          .that()
          .areAnnotatedWith(Disabled.class)
          .should(declareDisabledReasonOnClass())
          .because(DISABLED_REASON_FIX)
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
                      + "直し方：共有構成を @Import するか、それを内蔵する合成アノテーションを使う。"));
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
