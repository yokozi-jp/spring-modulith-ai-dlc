package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.DemoApplication;
import com.example.demo.shared.infrastructure.persistence.ModuleRoles;
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
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.PlainSQL;
import org.jooq.conf.ParamType;
import org.jooq.conf.Settings;
import org.jooq.conf.StatementType;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DBアクセスの規約（docs/database/postgresql-concurrency-control.md、docs/database/jooq-usage.md）をプロダクションコードへ静的に強制する。
 *
 * <p>トランザクションの分離レベルの指定、jOOQのPlain
 * SQLのAPI、文字列をそのままSQLに出力するAPI、識別子の名前を作るAPI、スキーマ名の出力の無効化、文の種類とバインド変数の設定の変更を検出する。テストコードと生成コードは {@link
 * ProductionCodeOnly} で対象外にする。
 *
 * <p>分離レベルの検査は、Springの {@code @Transactional} をクラスかメソッドに直接付けた場合だけを対象にし、それを含む合成アノテーションは対象にしない。
 */
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
// 規則とその組み立てを 1 つのクラスに置くため、メソッドが多い。
@SuppressWarnings("PMD.TooManyMethods")
class DatabaseConventionsArchTest {

  /** プロダクションコードの基底パッケージ。 */
  private static final String BASE_PACKAGE = DemoApplication.class.getPackageName();

  /** jOOQ の生成クラスのパッケージ。 */
  private static final String JOOQ_PACKAGE = BASE_PACKAGE + ".jooq";

  /** 分離レベルの指定を禁止する属性の名前。 */
  private static final String ISOLATION = "isolation";

  /** {@code @Transactional} に {@code isolation} を指定せず、既定のREAD COMMITTEDで動かす。 */
  @ArchTest /* package */ static final ArchRule transactionIsolationIsNotDeclared = isolationRule();

  /** {@code @PlainSQL} の付いたjOOQのAPIを呼ばず、メソッド参照もしない。 */
  @ArchTest /* package */ static final ArchRule plainSqlApisAreNotUsed = plainSqlRule();

  /** 文字列をそのままSQLに出力する {@code DSL.unquotedName} と {@code DSL.keyword} を呼ばず、メソッド参照もしない。 */
  @ArchTest /* package */ static final ArchRule verbatimSqlApisAreNotUsed = verbatimSqlRule();

  /**
   * 識別子の名前を作る {@code DSL.name}、{@code DSL.quotedName}、{@code DSL.systemName}、{@code Name.append}
   * を、引数の型によらず呼ばず、メソッド参照もしない。
   */
  @ArchTest /* package */ static final ArchRule nameBuildingApisAreNotUsed = nameBuildingRule();

  /**
   * {@code Settings.withRenderSchema} と {@code setRenderSchema} を呼ばず、メソッド参照もしない。
   *
   * <p>バイトコードには引数の値が残らず、{@code false} だけを検出できない。既定値が {@code true} なので、呼び出しそのものを禁止する。
   */
  @ArchTest /* package */ static final ArchRule renderSchemaIsNotChanged = renderSchemaRule();

  /** {@code Settings} の {@code statementType} と {@code paramType} を変えるメソッドを呼ばず、メソッド参照もしない。 */
  @ArchTest /* package */
  static final ArchRule statementTypeAndParamTypeAreNotChanged = statementTypeAndParamTypeRule();

  /** 機能モジュールの Persistence Adapter は、自分のスキーマの生成クラスだけを使う。 */
  @ArchTest /* package */
  static final ArchRule persistenceAdaptersUseOnlyOwnGeneratedSchema =
      ownGeneratedSchemaRule(BASE_PACKAGE, JOOQ_PACKAGE, ModuleRoles.MODULES);

  /** 機能モジュールのコードは、{@code JdbcTemplate} と生の JDBC を使わない。 */
  @ArchTest /* package */
  static final ArchRule moduleCodeDoesNotUseJdbc =
      moduleCodeDoesNotUseJdbcRule(BASE_PACKAGE, ModuleRoles.MODULES);

  /** 機能モジュールのコードは、接続の設定を変える {@code DSLContext} の API を使わない。 */
  @ArchTest /* package */
  static final ArchRule moduleCodeDoesNotChangeConnectionSettings =
      moduleCodeDoesNotChangeConnectionSettingsRule(BASE_PACKAGE, ModuleRoles.MODULES);

  @Test
  @DisplayName("DB規約に反するコードを拒否する")
  // JavaClasses は ArchUnit の import 結果を表す公開 API 型であり、インタフェースへ置き換えられない。
  @SuppressWarnings("PMD.LooseCoupling")
  void databaseConventionBypassesAreRejected() {
    // フィクスチャを実際に呼び出してIDEにも使用済みと認識させる。拒否判定は続くArchUnitの検査が担う。
    assertThat(DatabaseConventionBypass.useForbiddenApis()).hasSize(14);

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
    assertThatThrownBy(() -> verbatimSqlRule().check(bypassClass))
        .as("文字列をそのまま出力するAPI")
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("calls method <org.jooq.impl.DSL.unquotedName(java.lang.String)>")
        .hasMessageContaining(
            "references method <org.jooq.impl.DSL.unquotedName(java.lang.String)>")
        .hasMessageContaining("calls method <org.jooq.impl.DSL.keyword(java.lang.String)>");
    assertThatThrownBy(() -> nameBuildingRule().check(bypassClass))
        .as("識別子の名前を作るAPI")
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("calls method <org.jooq.impl.DSL.name(java.lang.String)>")
        .hasMessageContaining("references method <org.jooq.impl.DSL.name(java.lang.String)>")
        .hasMessageContaining("calls method <org.jooq.impl.DSL.quotedName(java.lang.String)>")
        .hasMessageContaining("calls method <org.jooq.impl.DSL.systemName(java.lang.String)>")
        .hasMessageContaining("calls method <org.jooq.Name.append(java.lang.String)>");
    assertThatThrownBy(() -> renderSchemaRule().check(bypassClass))
        .as("スキーマ名の出力の無効化")
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(
            "calls method <org.jooq.conf.Settings.withRenderSchema(java.lang.Boolean)>")
        .hasMessageContaining(
            "references method <org.jooq.conf.Settings.withRenderSchema(java.lang.Boolean)>");
    assertThatThrownBy(() -> statementTypeAndParamTypeRule().check(bypassClass))
        .as("文の種類とバインド変数の設定の変更")
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(
            "calls method <org.jooq.conf.Settings.withStatementType(org.jooq.conf.StatementType)>")
        .hasMessageContaining(
            "calls method <org.jooq.conf.Settings.withParamType(org.jooq.conf.ParamType)>");
  }

  /**
   * 機能モジュールの {@code infrastructure.persistence} が、自分のモジュールのスキーマの生成クラスだけを使う規則を組み立てる。
   *
   * @param basePackage 機能モジュールを置く基底パッケージ
   * @param jooqPackage jOOQ の生成クラスの基底パッケージ
   * @param modules ロールとスキーマを持つ機能モジュール
   */
  /* package */ static ArchRule ownGeneratedSchemaRule(
      final String basePackage, final String jooqPackage, final Set<String> modules) {
    return classes()
        .that()
        .resideInAnyPackage(modulePackages(basePackage, modules, ".infrastructure.persistence.."))
        .should(useOnlyOwnGeneratedSchema(basePackage, jooqPackage))
        .allowEmptyShould(true)
        .because(
            "モジュールのロールは自分のスキーマだけを読み書きでき、別のモジュールのスキーマを使うと実行時に権限エラーになるため。"
                + "別のモジュールの値は、そのモジュールの公開 API かイベントで受け取る。"
                + "直し方：自分のモジュールのスキーマ以外の生成クラスを使う処理を、そのスキーマを持つモジュールへ移す。"
                + "規約：docs/database/connections.md、docs/adr/ADR-094-switch-to-module-database-roles.md");
  }

  /** 機能モジュールのコードが、{@code JdbcTemplate} と生の JDBC を使わない規則を組み立てる。 */
  /* package */ static ArchRule moduleCodeDoesNotUseJdbcRule(
      final String basePackage, final Set<String> modules) {
    return noClasses()
        .that()
        .resideInAnyPackage(modulePackages(basePackage, modules, ".."))
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
        .allowEmptyShould(true)
        .because(
            "JdbcTemplate と生の JDBC は、トランザクションの初めに切り替えたモジュールのロールを迂回し、jOOQ の生成クラスで守る境界の外に出るため。"
                + "直し方：jOOQ の生成クラスとメタモデルで書き、JDBC の型を使わない。"
                + "規約：docs/database/jooq-usage.md、docs/adr/ADR-094-switch-to-module-database-roles.md");
  }

  /** 機能モジュールのコードが、接続の設定を変える {@code DSLContext} と {@code DSL} の API を使わない規則を組み立てる。 */
  /* package */ static ArchRule moduleCodeDoesNotChangeConnectionSettingsRule(
      final String basePackage, final Set<String> modules) {
    return noClasses()
        .that()
        .resideInAnyPackage(modulePackages(basePackage, modules, ".."))
        .should()
        .accessTargetWhere(
            JavaAccess.Predicates.target(
                AccessTarget.Predicates.declaredIn(DSLContext.class)
                    .or(AccessTarget.Predicates.declaredIn(DSL.class))
                    .and(
                        HasName.Predicates.nameMatching(
                            "set|setLocal|setSchema|setCatalog|connection|connectionResult"))))
        .allowEmptyShould(true)
        .because(
            "SET と SET ROLE を実行すると、トランザクションの初めに切り替えたロールとテナントを書き換えられるため。"
                + "接続を直接操作する connection も同じ抜け道になる。"
                + "直し方：DSLContext の set、setLocal、setSchema、setCatalog、connection、connectionResult と、DSL の set、setLocal、setSchema、setCatalog の呼び出しを削除する。"
                + "規約：docs/database/connections.md、docs/adr/ADR-094-switch-to-module-database-roles.md");
  }

  private static String[] modulePackages(
      final String basePackage, final Set<String> modules, final String suffix) {
    return modules.stream()
        .map(module -> basePackage + "." + module + suffix)
        .toArray(String[]::new);
  }

  private static ArchCondition<JavaClass> useOnlyOwnGeneratedSchema(
      final String basePackage, final String jooqPackage) {
    return new ArchCondition<>("use only the generated classes of its own schema") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        final String module =
            item.getPackageName().substring(basePackage.length() + 1).split("\\.")[0];
        final String own = jooqPackage + "." + module;
        item.getDirectDependenciesFromSelf().stream()
            .map(dependency -> dependency.getTargetClass())
            .filter(target -> isGenerated(target.getPackageName(), jooqPackage))
            .filter(target ->
                !target.getPackageName().equals(own)
                    && !target.getPackageName().startsWith(own + "."))
            .forEach(target ->
                events.add(
                    SimpleConditionEvent.violated(
                        item,
                        item.getName()
                            + " は "
                            + module
                            + " のスキーマ以外の生成クラス "
                            + target.getName()
                            + " を使っている")));
      }
    };
  }

  private static boolean isGenerated(final String packageName, final String jooqPackage) {
    return packageName.equals(jooqPackage) || packageName.startsWith(jooqPackage + ".");
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

  private static ArchRule verbatimSqlRule() {
    return noClasses()
        .should()
        .accessTargetWhere(
            JavaAccess.Predicates.target(
                AccessTarget.Predicates.declaredIn(JavaClass.Predicates.assignableTo(DSL.class))
                    .and(HasName.Predicates.nameMatching("unquotedName|keyword"))))
        .because(
            "@PlainSQL は付かないが、文字列を引用符なしでそのまま SQL に出力し、リクエストの値を渡すとインジェクションが成立するため。"
                + "直し方：unquotedName と keyword の呼び出しを削除し、テーブルとカラムは生成されたクラスから参照する。"
                + "規約：docs/database/jooq-usage.md");
  }

  private static ArchRule nameBuildingRule() {
    return noClasses()
        .should()
        .accessTargetWhere(
            JavaAccess.Predicates.target(
                AccessTarget.Predicates.declaredIn(JavaClass.Predicates.assignableTo(DSL.class))
                    .and(HasName.Predicates.nameMatching("name|quotedName|systemName"))
                    .or(
                        AccessTarget.Predicates.declaredIn(
                                JavaClass.Predicates.assignableTo(Name.class))
                            .and(HasName.Predicates.name("append")))))
        .because(
            "リクエストの値を渡すと、生成済みのメタモデルにない列やテーブルを参照できるため。"
                + "直し方：name、quotedName、systemName、Name.append の呼び出しを削除し、テーブルとカラムは生成されたクラスから参照する。"
                + "並び順は domain.model の enum を switch 式で生成済みの列に写す。"
                + "規約：docs/database/jooq-usage.md、docs/backend/class-roles/jooq-repository.md");
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

  private static ArchRule statementTypeAndParamTypeRule() {
    return noClasses()
        .should()
        .accessTargetWhere(
            JavaAccess.Predicates.target(
                AccessTarget.Predicates.declaredIn(Settings.class)
                    .and(HasName.Predicates.nameMatching("(with|set)(StatementType|ParamType)"))))
        .because(
            "値を SQL へ埋め込まず、バインド変数で渡すため。"
                + "直し方：statementType と paramType は既定の PREPARED_STATEMENT と INDEXED のまま変えず、withStatementType、setStatementType、withParamType、setParamType の呼び出しを削除する。"
                + "規約：docs/database/jooq-usage.md");
  }

  private static ArchCondition<JavaClass> notDeclareTransactionIsolation() {
    return new ArchCondition<>("not declare @Transactional isolation") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        Stream.concat(
                Stream.of(item).filter(owner -> declaresIsolation(owner.getAnnotations())),
                item.getMethods().stream()
                    .filter(owner -> declaresIsolation(owner.getAnnotations())))
            .forEach(owner ->
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
        .anyMatch(annotation ->
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
      final Function<String, Name> unquotedName = DSL::unquotedName;
      final Function<String, Name> name = DSL::name;
      return List.of(
          new DatabaseConventionBypass().repeatableRead(),
          plainSqlField.apply("x"),
          new Settings().withRenderSchema(false),
          renderSchema.apply(false),
          // 引用符を付けずに出力するAPIは禁止する。
          DSL.unquotedName("x"),
          unquotedName.apply("x"),
          // キーワードとして文字列をそのまま出力するAPIは禁止する。
          DSL.keyword("x"),
          // 文字列から識別子の名前を作るAPIは禁止する。
          DSL.name("x"),
          name.apply("x"),
          // 引用符の付け方を指定して識別子の名前を作るAPIも禁止する。
          DSL.quotedName("x"),
          DSL.systemName("x"),
          // 名前に文字列を足して識別子の名前を作るAPIも禁止する。
          name.apply("y").append("x"),
          // 値をSQLへ埋め込む設定は禁止する。
          new Settings().withStatementType(StatementType.STATIC_STATEMENT),
          new Settings().withParamType(ParamType.INLINED));
    }

    // 分離レベルを指定した @Transactional は禁止する。
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    private Field<Object> repeatableRead() {
      // 文字列のSQLを受け取るPlain SQLのAPIは禁止する。
      return DSL.field("x");
    }
  }
}
