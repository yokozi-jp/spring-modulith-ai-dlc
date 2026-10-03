package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jooq.Field;
import org.jooq.PlainSQL;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DBアクセスの規約（docs/database/postgresql-concurrency-control.md、docs/database/jooq-usage.md）をプロダクションコードへ静的に強制する。
 *
 * <p>トランザクションの分離レベルの指定、jOOQのPlain SQLのAPI、スキーマ名の出力の無効化を検出する。テストコードと生成コードは {@link
 * ProductionCodeOnly} で対象外にする。
 *
 * <p>分離レベルの検査は、Springの {@code @Transactional} をクラスかメソッドに直接付けた場合だけを対象にし、それを含む合成アノテーションは対象にしない。
 */
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class DatabaseConventionsArchTest {

  /** 分離レベルの指定を禁止する属性の名前。 */
  private static final String ISOLATION = "isolation";

  /** {@code @Transactional} に {@code isolation} を指定せず、既定のREAD COMMITTEDで動かす。 */
  @ArchTest /* package */ static final ArchRule transactionIsolationIsNotDeclared = isolationRule();

  /** {@code @PlainSQL} の付いたjOOQのAPIを呼ばず、メソッド参照もしない。 */
  @ArchTest /* package */ static final ArchRule plainSqlApisAreNotUsed = plainSqlRule();

  /**
   * {@code Settings.withRenderSchema} と {@code setRenderSchema} を呼ばず、メソッド参照もしない。
   *
   * <p>バイトコードには引数の値が残らず、{@code false} だけを検出できない。既定値が {@code true} なので、呼び出しそのものを禁止する。
   */
  @ArchTest /* package */ static final ArchRule renderSchemaIsNotChanged = renderSchemaRule();

  @Test
  @DisplayName("DB規約に反するコードを拒否する")
  // JavaClasses は ArchUnit の import 結果を表す公開 API 型であり、インタフェースへ置き換えられない。
  @SuppressWarnings("PMD.LooseCoupling")
  void databaseConventionBypassesAreRejected() {
    // フィクスチャを実際に呼び出してIDEにも使用済みと認識させる。拒否判定は続くArchUnitの検査が担う。
    assertThat(DatabaseConventionBypass.useForbiddenApis()).hasSize(4);

    final JavaClasses bypassClass =
        new ClassFileImporter().importClasses(DatabaseConventionBypass.class);
    final String fixture = DatabaseConventionBypass.class.getName();

    assertThatThrownBy(() -> isolationRule().check(bypassClass))
        .as("分離レベルの指定")
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Class <" + fixture + "> の @Transactional")
        .hasMessageContaining("Method <" + fixture + ".repeatableRead()> の @Transactional")
        .hasMessageContaining("isolation を削除する");
    assertThatThrownBy(() -> plainSqlRule().check(bypassClass))
        .as("Plain SQL のAPI")
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("calls method <org.jooq.impl.DSL.field(java.lang.String)>")
        .hasMessageContaining("references method <org.jooq.impl.DSL.field(java.lang.String)>");
    assertThatThrownBy(() -> renderSchemaRule().check(bypassClass))
        .as("スキーマ名の出力の無効化")
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(
            "calls method <org.jooq.conf.Settings.withRenderSchema(java.lang.Boolean)>")
        .hasMessageContaining(
            "references method <org.jooq.conf.Settings.withRenderSchema(java.lang.Boolean)>");
  }

  private static ArchRule isolationRule() {
    return classes()
        .should(notDeclareTransactionIsolation())
        .because(
            "PostgreSQL では REPEATABLE READ 以上にすると、直列化の失敗によるエラーが積極的に起きるため。"
                + "直し方：@Transactional から isolation を削除し、業務処理に必要な整合性は SELECT ... FOR UPDATE の行ロックで守る。"
                + "規約：docs/database/postgresql-concurrency-control.md");
  }

  private static ArchRule plainSqlRule() {
    return noClasses()
        .should()
        .accessTargetWhere(
            JavaAccess.Predicates.target(CanBeAnnotated.Predicates.annotatedWith(PlainSQL.class)))
        .because(
            "文字列の SQL では、jOOQ を採用した理由であるスキーマとのコンパイル時の照合を失うため。"
                + "直し方：@PlainSQL の付いた API の呼び出しを削除し、テーブルとカラムは生成されたクラスから参照する。"
                + "規約：docs/database/jooq-usage.md、docs/adr/ADR-003-adopt-jooq-for-data-access.md");
  }

  private static ArchRule renderSchemaRule() {
    return noClasses()
        .should()
        .accessTargetWhere(
            JavaAccess.Predicates.target(
                AccessTarget.Predicates.declaredIn(Settings.class)
                    .and(HasName.Predicates.nameMatching("(with|set)RenderSchema"))))
        .because(
            "search_path による暗黙の振り分けを避け、SQL をスキーマ名で修飾したままにするため。"
                + "直し方：renderSchema は既定の true のまま変えず、withRenderSchema と setRenderSchema の呼び出しを削除する。"
                + "規約：docs/database/jooq-usage.md、docs/adr/ADR-011-use-module-owned-database-schemas.md");
  }

  private static ArchCondition<JavaClass> notDeclareTransactionIsolation() {
    return new ArchCondition<>("not declare @Transactional isolation") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        Stream.concat(
                Stream.of(item).filter(owner -> declaresIsolation(owner.getAnnotations())),
                item.getMethods().stream()
                    .filter(owner -> declaresIsolation(owner.getAnnotations())))
            .forEach(
                owner ->
                    events.add(
                        SimpleConditionEvent.violated(
                            item,
                            owner.getDescription()
                                + " の @Transactional が isolation を指定している。"
                                + "直し方：isolation を削除する。")));
      }
    };
  }

  private static boolean declaresIsolation(final Set<? extends JavaAnnotation<?>> annotations) {
    return annotations.stream()
        .anyMatch(
            annotation ->
                annotation.getRawType().isEquivalentTo(Transactional.class)
                    && annotation.tryGetExplicitlyDeclaredProperty(ISOLATION).isPresent());
  }

  /** ArchUnit の拒否経路を検証するため、DB規約に反するコードを意図的に含めたフィクスチャ。 */
  @Transactional(isolation = Isolation.SERIALIZABLE)
  private static final class DatabaseConventionBypass {

    private static List<Object> useForbiddenApis() {
      // メソッド参照もPlain SQLのAPIの利用として検出する。
      final Function<String, Field<Object>> plainSqlField = DSL::field;
      final Function<Boolean, Settings> renderSchema = new Settings()::withRenderSchema;
      return List.of(
          new DatabaseConventionBypass().repeatableRead(),
          plainSqlField.apply("x"),
          new Settings().withRenderSchema(false),
          renderSchema.apply(false));
    }

    // 分離レベルを指定した @Transactional は禁止する。
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    private Field<Object> repeatableRead() {
      // 文字列のSQLを受け取るPlain SQLのAPIは禁止する。
      return DSL.field("x");
    }
  }
}
