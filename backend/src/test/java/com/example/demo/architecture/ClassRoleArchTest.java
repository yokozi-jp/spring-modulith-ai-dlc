package com.example.demo.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.lang.conditions.ArchConditions.be;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DefaultRecordMapper;
import org.jooq.impl.DefaultRecordUnmapper;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * クラスの役割（docs/backend/class-roles/）ごとの形と命名をプロダクションコードへ強制する。
 *
 * <p>基底パッケージを含む規則は {@code xxxRule(basePackage)} のファクトリで組み立て、{@link ArchitectureRuleFixtureTest}
 * がフィクスチャのパッケージで同じ規則を再利用する。
 */
// ArchUnit の JUnit 5 エンジンが @ArchTest フィールドを読むため、規則ファクトリだけを持つクラスでもユーティリティクラスにしない。
@SuppressWarnings({
  "PMD.TestClassWithoutTestCases",
  "PMD.TooManyStaticImports",
  "PMD.UseUtilityClass"
})
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class ClassRoleArchTest {

  /** プロダクションコードの基底パッケージ。 */
  private static final String BASE_PACKAGE = DemoApplication.class.getPackageName();

  /** CommandHandler の名前の接尾辞。 */
  private static final String COMMAND_HANDLER = "CommandHandler";

  /** クラスの役割を決めた ADR のパス。 */
  private static final String ADR_050 = "docs/adr/ADR-050-define-backend-class-roles-and-naming.md";

  /** jOOQ の Repository の規約のパス。 */
  private static final String JOOQ_REPOSITORY_DOC = "docs/backend/class-roles/jooq-repository.md";

  /** モジュールルートには record、enum、{@code *Queries} interface だけを置く。 */
  @ArchTest
  /* package */ static final ArchRule moduleRootTypesAreRecordsEnumsOrQueries =
      moduleRootTypesAreRecordsEnumsOrQueriesRule(BASE_PACKAGE);

  /** Application の {@code @Service} は役割名で終える。 */
  @ArchTest
  /* package */ static final ArchRule applicationServicesHaveRoleNames =
      classes()
          .that()
          .areAnnotatedWith(Service.class)
          .and()
          .resideInAPackage("..application..")
          .should()
          .haveSimpleNameEndingWith(COMMAND_HANDLER)
          .orShould()
          .haveSimpleNameEndingWith("QueryService")
          .orShould()
          .haveSimpleNameEndingWith("Listener")
          .allowEmptyShould(true)
          .because(
              "クラス名の接尾辞だけで、更新、参照、イベントの受信のどれかが分かるようにするため。"
                  + "直し方：状態を変えるユースケースは <UseCase>CommandHandler、"
                  + "参照は <Feature>QueryService、イベントの受信は <Event>Listener と命名する。"
                  + "規約：docs/backend/layers.md、"
                  + ADR_050);

  /**
   * CommandHandler の public メソッドは {@code @Transactional} の {@code handle(<UseCase>Command)} だけにする。
   */
  @ArchTest
  /* package */ static final ArchRule commandHandlersExposeOnlyTransactionalHandle =
      classes()
          .that()
          .haveSimpleNameEndingWith(COMMAND_HANDLER)
          .should(exposeOnlyTransactionalHandle())
          .allowEmptyShould(true)
          .because(
              "複数のユースケースを一つのクラスに並べると、依存とトランザクション境界がユースケースごとに分かれず、"
                  + "クラスが大きくなるため。"
                  + "直し方：public メソッドを @Transactional を付けた "
                  + "<UseCase>Result handle(<UseCase>Command command) の一つにし、"
                  + "別のユースケースは別の CommandHandler に分ける。"
                  + "規約：docs/backend/class-roles/command-handler.md、"
                  + ADR_050);

  /** Command と Result は Application の record にする。 */
  @ArchTest
  /* package */ static final ArchRule commandsAndResultsAreApplicationRecords =
      commandsAndResultsAreApplicationRecordsRule(BASE_PACKAGE);

  /** CommandHandler から別の CommandHandler への依存を禁止する。 */
  @ArchTest
  /* package */ static final ArchRule commandHandlersDoNotDependOnOtherCommandHandlers =
      noClasses()
          .that()
          .haveSimpleNameEndingWith(COMMAND_HANDLER)
          .should()
          .dependOnClassesThat()
          .haveSimpleNameEndingWith(COMMAND_HANDLER)
          .allowEmptyShould(true)
          .because(
              "一つのユースケースを一つのトランザクションで進めるという CommandHandler の定義を保ち、"
                  + "ユースケースが別のユースケースを呼んで連鎖する形を防ぐため。"
                  + "直し方：共通の業務規則は集約か Domain Service へ移し、"
                  + "後続の処理はイベントを発行して Listener から別の CommandHandler を呼ぶ。"
                  + "規約：docs/backend/class-roles/command-handler.md、"
                  + ADR_050);

  /** {@code @ApplicationModuleListener} は Application の {@code <Event>Listener.on} にだけ付ける。 */
  @ArchTest
  /* package */ static final ArchRule moduleListenersAreApplicationListeners =
      methods()
          .that()
          .areAnnotatedWith(ApplicationModuleListener.class)
          .should()
          .haveName("on")
          .andShould()
          .beDeclaredInClassesThat()
          .resideInAPackage("..application..")
          .andShould()
          .beDeclaredInClassesThat()
          .haveSimpleNameEndingWith("Listener")
          .allowEmptyShould(true)
          .because(
              "@ApplicationModuleListener は新しいトランザクションを開き、受信のメソッドがトランザクション境界になるため、"
                  + "境界を Application に置く。"
                  + "直し方：イベントの受信は受信側モジュールの application に置く <Event>Listener の on メソッドへ移す。"
                  + "規約：docs/backend/class-roles/listener.md、"
                  + ADR_050);

  /** Listener は {@code on} だけを公開し、1 つの CommandHandler だけを呼ぶ。 */
  @ArchTest
  /* package */ static final ArchRule listenersExposeOnlyOnAndCallOneCommandHandler =
      classes()
          .that()
          .haveSimpleNameEndingWith("Listener")
          .and()
          .resideInAPackage("..application..")
          .should(exposeOnlyOnAndCallOneCommandHandler())
          .allowEmptyShould(true)
          .because(
              "一つの on から複数の CommandHandler を呼ぶと、一つが失敗したときにイベントが再配信され、"
                  + "成功した処理も繰り返されるため。"
                  + "直し方：public メソッドを @ApplicationModuleListener を付けた void on(<Event> event) の一つにし、"
                  + "イベントから Command を作って一つの CommandHandler の handle を呼ぶ。"
                  + "規約：docs/backend/class-roles/listener.md、"
                  + ADR_050);

  /** Request と Response は {@code presentation.web} の record にする。 */
  @ArchTest
  /* package */ static final ArchRule requestsAndResponsesArePresentationWebRecords =
      requestsAndResponsesArePresentationWebRecordsRule(BASE_PACKAGE);

  /** QueryService は自モジュールの {@code *Queries} を実装し、public メソッドを読み取り専用トランザクションにする。 */
  @ArchTest
  /* package */ static final ArchRule queryServicesImplementModuleQueries =
      classes()
          .that()
          .haveSimpleNameEndingWith("QueryService")
          .should(implementModuleQueriesWithReadOnlyTransactions())
          .allowEmptyShould(true)
          .because(
              "参照の入口を <Feature>Queries に限り、読み取り専用のトランザクションで参照中の書き込みを DB に拒否させるため。"
                  + "直し方：QueryService を <モジュール>.application に置き、"
                  + "同じモジュールのルートにある <Feature>Queries を実装し、"
                  + "public メソッドすべてに @Transactional(readOnly = true) を付ける。"
                  + "規約：docs/backend/class-roles/query-service.md、"
                  + ADR_050);

  /** 列と項目を対応づけるライブラリと jOOQ の {@code DefaultRecordMapper} を使わない。 */
  @ArchTest
  /* package */ static final ArchRule mappingLibrariesAreNotUsed =
      noClasses()
          .should()
          .dependOnClassesThat(
              resideInAnyPackage(
                      "org.mapstruct..",
                      "org.modelmapper..",
                      "com.github.dozermapper..",
                      "org.dozer..")
                  .or(type(DefaultRecordMapper.class))
                  .or(type(DefaultRecordUnmapper.class)))
          .because(
              "jOOQ と集約の変換を Jooq<Aggregate>Repository の select の列に収め、"
                  + "列と引数の数や型の食い違いをコンパイルで見つけるため。"
                  + "直し方：変換を Jooq<Aggregate>Repository に convertFrom、multiset、Records.mapping で書く。"
                  + "規約："
                  + JOOQ_REPOSITORY_DOC
                  + "、"
                  + ADR_050);

  /**
   * 名前のリフレクションで対応づける jOOQ のメソッドの名前のうち、{@code Class} を受け取るときだけ禁止するもの（ほかに名前が {@code Into}
   * で終わるメソッド）。{@code intoArray}、{@code intoSet}、{@code fetch(Field, Class)} は列の型の変換なので含めない。
   */
  private static final Set<String> JOOQ_CLASS_MAPPING_METHODS =
      Set.of("into", "intoMap", "intoGroups", "fetchMap", "fetchGroups");

  /** 名前のリフレクションで対応づける jOOQ のメソッドのうち、{@code Object} を受け取るときに禁止するもの。 */
  private static final Set<String> JOOQ_OBJECT_MAPPING_METHODS =
      Set.of("into", "from", "newRecord");

  /** jOOQ の、名前のリフレクションでの読み書きの対応づけを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule jooqReflectionMappingIsNotUsed =
      noClasses()
          .should()
          .callMethodWhere(
              DescribedPredicate.describe(
                  "jOOQ の into、intoMap、intoGroups、fetchMap、fetchGroups、*Into の Class を受け取る呼び出しか、"
                      + "Record の into(Object)、from(Object)、DSLContext の newRecord(Table, Object)",
                  ClassRoleArchTest::isJooqReflectionMapping))
          .because(
              "名前のリフレクションで対応づけると、列や項目の名前を変えたときの誤りがコンパイルで見つからず、"
                  + "集約が restore と値オブジェクトの検証を通らないため。"
                  + "直し方：into(Class)、fetchInto(Class)、fetchMap(Field, Class)、from(Object) などを、"
                  + "convertFrom、fetch(Records.mapping(<Aggregate>::restore))、set(列, 値) に置き換える。"
                  + "規約："
                  + JOOQ_REPOSITORY_DOC
                  + "、"
                  + ADR_050);

  /** モジュールルートの型を record、enum、{@code *Queries} interface に限る規則を組み立てる。 */
  /* package */ static ArchRule moduleRootTypesAreRecordsEnumsOrQueriesRule(
      final String basePackage) {
    return classes()
        .that()
        .resideInAPackage(basePackage + ".*")
        .and()
        .doNotHaveSimpleName("package-info")
        .should(
            be(
                DescribedPredicate.describe(
                    "a record, an enum, or an interface named *Queries",
                    (JavaClass javaClass) ->
                        javaClass.isRecord()
                            || javaClass.isEnum()
                            || (javaClass.isInterface()
                                && javaClass.getSimpleName().endsWith("Queries")))))
        .allowEmptyShould(true)
        .because(
            "モジュールルートは他モジュールへ公開する契約であり、他モジュールの状態は同期で変えずイベントで伝えるため。"
                + "直し方：ルートには <Feature>Queries と、参照の結果、検索条件、イベントの record と enum だけを置き、"
                + "Command と CommandHandler は application へ移す。"
                + "規約：docs/backend/architecture.md、"
                + ADR_050);
  }

  /** {@code *Command} と {@code *Result} を {@code <module>.application} の record に限る規則を組み立てる。 */
  /* package */ static ArchRule commandsAndResultsAreApplicationRecordsRule(
      final String basePackage) {
    return classes()
        .that(
            resideInAPackage(basePackage + ".*..")
                .and(simpleNameEndingWith("Command").or(simpleNameEndingWith("Result"))))
        .should()
        .beRecords()
        .andShould()
        .resideInAPackage(basePackage + ".*.application")
        .allowEmptyShould(true)
        .because(
            "Command と Result はユースケースの入出力であり、モジュールルートに置いて他モジュールから同期で呼ばせないため。"
                + "直し方：<UseCase>Command と <UseCase>Result を <モジュール>.application の record にする。"
                + "規約：docs/backend/class-roles/command.md、docs/backend/class-roles/result.md、"
                + ADR_050);
  }

  /** {@code *Request} と {@code *Response} を {@code presentation.web} の record に限る規則を組み立てる。 */
  /* package */ static ArchRule requestsAndResponsesArePresentationWebRecordsRule(
      final String basePackage) {
    return classes()
        .that(
            resideInAPackage(basePackage + ".*..")
                .and(simpleNameEndingWith("Request").or(simpleNameEndingWith("Response"))))
        .should()
        .beRecords()
        .andShould()
        .resideInAPackage("..presentation.web..")
        .allowEmptyShould(true)
        .because(
            "リクエストボディと応答は HTTP の利用者との契約であり、ユースケースの入力や参照の結果とは別に変わるため。"
                + "直し方：<UseCase>Request と <QueryResult>Response を <モジュール>.presentation.web の record にする。"
                + "規約：docs/backend/class-roles/request.md、docs/backend/class-roles/response.md、"
                + ADR_050);
  }

  private static ArchCondition<JavaClass> exposeOnlyTransactionalHandle() {
    return new ArchCondition<>("expose only a @Transactional handle(*Command) returning *Result") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        final List<JavaMethod> publicMethods = publicMethodsOf(item);
        final boolean valid =
            publicMethods.size() == 1 && isTransactionalHandle(publicMethods.getFirst());
        if (!valid) {
          events.add(
              SimpleConditionEvent.violated(
                  item, item.getFullName() + " の public メソッドが " + publicMethods + " である。"));
        }
      }
    };
  }

  private static boolean isTransactionalHandle(final JavaMethod method) {
    return "handle".equals(method.getName())
        && method.getRawParameterTypes().size() == 1
        && method.getRawParameterTypes().getFirst().getSimpleName().endsWith("Command")
        && method.getRawReturnType().getSimpleName().endsWith("Result")
        && method.isAnnotatedWith(Transactional.class);
  }

  private static ArchCondition<JavaClass> exposeOnlyOnAndCallOneCommandHandler() {
    return new ArchCondition<>("expose only on(event) and call exactly one *CommandHandler") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        final List<JavaMethod> publicMethods = publicMethodsOf(item);
        final boolean exposesOnlyOn =
            publicMethods.size() == 1 && isModuleListenerOn(publicMethods.getFirst());
        final Set<String> handlers =
            item.getMethodCallsFromSelf().stream()
                .map(JavaMethodCall::getTargetOwner)
                .map(JavaClass::getFullName)
                .filter(name -> name.endsWith(COMMAND_HANDLER))
                .collect(Collectors.toSet());
        if (!exposesOnlyOn || handlers.size() != 1) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getFullName()
                      + " の public メソッドが "
                      + publicMethods
                      + "、呼び出す CommandHandler が "
                      + handlers
                      + " である。"));
        }
      }
    };
  }

  private static boolean isModuleListenerOn(final JavaMethod method) {
    return "on".equals(method.getName())
        && method.getRawParameterTypes().size() == 1
        && "void".equals(method.getRawReturnType().getName())
        && method.isAnnotatedWith(ApplicationModuleListener.class);
  }

  private static ArchCondition<JavaClass> implementModuleQueriesWithReadOnlyTransactions() {
    return new ArchCondition<>(
        "reside in <module>.application, implement <module>.*Queries, and be read-only") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        final String packageName = item.getPackageName();
        final String modulePackage =
            packageName.substring(0, Math.max(0, packageName.lastIndexOf('.')));
        final boolean implementsQueries =
            packageName.endsWith(".application")
                && item.getAllRawInterfaces().stream()
                    .anyMatch(
                        queries ->
                            queries.getSimpleName().endsWith("Queries")
                                && queries.getPackageName().equals(modulePackage));
        final List<JavaMethod> writableMethods =
            publicMethodsOf(item).stream()
                .filter(method -> !isReadOnlyTransactional(method))
                .toList();
        if (!implementsQueries || !writableMethods.isEmpty()) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getFullName()
                      + " は "
                      + modulePackage
                      + " の *Queries を実装していないか、readOnly でない public メソッド "
                      + writableMethods
                      + " を持つ。"));
        }
      }
    };
  }

  private static boolean isReadOnlyTransactional(final JavaMethod method) {
    return method
        .tryGetAnnotationOfType(Transactional.class)
        .map(Transactional::readOnly)
        .orElse(false);
  }

  /** 呼び出しが、名前のリフレクションで列と項目を対応づける jOOQ のメソッドかを返す。 */
  private static boolean isJooqReflectionMapping(final JavaMethodCall call) {
    final JavaClass owner = call.getTargetOwner();
    if (!resideInAnyPackage("org.jooq..", BASE_PACKAGE + ".jooq..").test(owner)) {
      return false;
    }
    final String name = call.getName();
    final List<JavaClass> parameters = call.getTarget().getRawParameterTypes();
    final boolean takesClass =
        parameters.stream().anyMatch(parameter -> parameter.isEquivalentTo(Class.class));
    final boolean takesObject =
        parameters.stream().anyMatch(parameter -> parameter.isEquivalentTo(Object.class));
    return takesClass && (JOOQ_CLASS_MAPPING_METHODS.contains(name) || name.endsWith("Into"))
        || takesObject
            && JOOQ_OBJECT_MAPPING_METHODS.contains(name)
            && (owner.isAssignableTo(Record.class) || owner.isAssignableTo(DSLContext.class));
  }

  /** 合成メソッドとブリッジメソッドを除いた、クラス自身が宣言する public メソッドを返す。 */
  private static List<JavaMethod> publicMethodsOf(final JavaClass javaClass) {
    return javaClass.getMethods().stream()
        .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
        .filter(method -> !method.getModifiers().contains(JavaModifier.SYNTHETIC))
        .filter(method -> !method.getModifiers().contains(JavaModifier.BRIDGE))
        .toList();
  }
}
