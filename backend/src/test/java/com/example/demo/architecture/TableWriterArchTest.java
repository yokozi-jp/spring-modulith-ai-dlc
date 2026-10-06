package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.jooq.ConnectionProvider;
import org.jooq.Constants;
import org.jooq.DAO;
import org.jooq.DMLQuery;
import org.jooq.DSLContext;
import org.jooq.Delete;
import org.jooq.InsertOnDuplicateStep;
import org.jooq.InsertQuery;
import org.jooq.LoaderOptionsStep;
import org.jooq.Merge;
import org.jooq.Query;
import org.jooq.RowCountQuery;
import org.jooq.UpdatableRecord;
import org.jooq.Update;
import org.jooq.WithStep;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 業務テーブルの UPDATE と DELETE を {@code TableWriter} に集め、楽観的ロックの版の比べ忘れを止める（ADR-054、
 * docs/database/postgresql-concurrency-control.md）。
 *
 * <p>基底パッケージを含む規則は {@code xxxRule(basePackage)} のファクトリで組み立て、{@link ArchitectureRuleFixtureTest}
 * がフィクスチャのパッケージで同じ規則を再利用する。
 *
 * <p>集約ルートは、{@code domain.model} にあり、引数のない {@code long lockNo()} を宣言する型とする。
 *
 * <p>画面の版は {@code ExpectedLockNo} と {@code VersionedCommand} で運ぶ。次の規則が、版の比べ忘れを止める。
 *
 * <ul>
 *   <li>R1 {@code commandsBuiltByPresentationForWritesAreVersioned}：presentation
 *       がコンストラクタ、コンストラクタ参照、 Command 自身を返す static factory で作る Command で保存する CommandHandler は、その
 *       Command を {@code VersionedCommand} にする。
 *   <li>R2 {@code onlyCommandHandlersUpdateOrDeleteAggregates}：Repository の {@code add}
 *       以外の書き込み（{@code update}、{@code delete}、集約ルートを受け取るメソッド）は CommandHandler だけが呼ぶ。
 *   <li>R3 {@code expectedLockNoIsCreatedOnlyByRequests}：{@code ExpectedLockNo} は {@code
 *       presentation.web} の Request だけが作る。
 *   <li>R4 {@code commandHandlersEnsureScreenLockNo}：{@code VersionedCommand} を受け取る CommandHandler
 *       の {@code handle} は、その中で直接 {@code ensureLockNo(ExpectedLockNo)} を呼ぶ。
 * </ul>
 *
 * <p>ponytail: 値の流れは追わない。{@code ensureLockNo} を別の集約のインスタンスに呼ぶ実装、業務の検査や外部の呼び出しの後に呼ぶ実装、 {@code
 * command.expectedLockNo()} 以外の値を渡す実装は検出できない。追うなら ArchUnit ではなく CommandHandler のテストで確かめる。
 */
// 規則のファクトリと、規則が使う判定を一つのクラスにまとめるため、メソッドの数の上限を外す。
@SuppressWarnings("PMD.TooManyMethods")
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class TableWriterArchTest {

  /** プロダクションコードの基底パッケージ。 */
  private static final String BASE_PACKAGE = DemoApplication.class.getPackageName();

  /** 書き込みの入口を置く shared のパッケージ（基底パッケージからの相対）。 */
  private static final String WRITER_PACKAGE = ".shared.infrastructure.persistence.";

  /** 楽観的ロックの語彙を置く shared のパッケージ（基底パッケージからの相対）。 */
  private static final String CONCURRENCY_PACKAGE = ".shared.concurrency.";

  /** 集約と Repository を置くパッケージ。 */
  private static final String DOMAIN_MODEL = ".domain.model";

  /** CommandHandler の名前の接尾辞。 */
  private static final String COMMAND_HANDLER = "CommandHandler";

  /** Repository の名前の接尾辞。 */
  private static final String REPOSITORY = "Repository";

  /** 集約の版を比べるメソッドの名前。 */
  private static final String ENSURE_LOCK_NO = "ensureLockNo";

  /** CommandHandler の入口のメソッドの名前。 */
  private static final String HANDLE = "handle";

  /** 新しい集約ルートを保存する Repository のメソッドの名前。 */
  private static final String ADD = "add";

  /** 集約ルートを保存する Repository のメソッドの名前。 */
  private static final String UPDATE = "update";

  /** 集約ルートを削除する Repository のメソッドの名前。 */
  private static final String DELETE = "delete";

  /** 版を比べて書く入口の名前。 */
  private static final String UPDATE_CHECKING_VERSION = "updateCheckingVersion";

  /** 版を比べて削除する入口の名前。 */
  private static final String DELETE_CHECKING_VERSION = "deleteCheckingVersion";

  /** 版を比べない入口の名前。 */
  private static final Set<String> UNVERSIONED_WRITES = Set.of("updateWhere", "deleteWhere");

  /** 楽観的ロックの規約と、この決定の ADR。 */
  private static final String DOCS =
      "docs/database/postgresql-concurrency-control.md、"
          + "docs/adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md";

  /**
   * {@code DSLContext}、{@code DSL}、{@code WithStep} の、UPDATE、DELETE、MERGE の入口と接続の直接の利用。
   *
   * <p>{@code Update} と {@code Delete} を作る入口をすべて禁じるため、{@code batch} や {@code subscribe}
   * のような問い合わせを受け取る実行の API は禁じなくてよい。
   */
  private static final Set<String> DSL_WRITES =
      Set.of(
          UPDATE,
          DELETE,
          "deleteFrom",
          "mergeInto",
          "updateQuery",
          "deleteQuery",
          "batchUpdate",
          "batchStore",
          "batchDelete",
          "batchMerge",
          "executeUpdate",
          "executeDelete",
          "connection",
          "connectionResult");

  /** {@code Update} と {@code Delete} に代入できる型の実行。名前が {@code fetch} で始まるものも含める。 */
  private static final Set<String> QUERY_EXECUTIONS =
      Set.of("execute", "executeAsync", "returning", "returningResult");

  /**
   * {@code Update} と {@code Delete} が代入できる汎用の問い合わせの型。
   *
   * <p>メソッド参照（{@code Update::execute}）のバイトコードには {@code execute} を宣言した {@code Query}
   * が残るため、この型の実行も禁じる。
   */
  private static final Set<String> GENERIC_QUERY_TYPES =
      Set.of(Query.class.getName(), RowCountQuery.class.getName(), DMLQuery.class.getName());

  /** {@code InsertOnDuplicateStep} と {@code InsertQuery} の UPSERT。 */
  private static final Set<String> UPSERTS =
      Set.of(
          "onConflict",
          "onConflictOnConstraint",
          "onConflictWhere",
          "onDuplicateKeyUpdate",
          "addValueForUpdate",
          "addValuesForUpdate");

  /** {@code UpdatableRecord} の書き込み。 */
  private static final Set<String> RECORD_WRITES = Set.of("store", UPDATE, DELETE, "merge");

  /** {@code DAO} の書き込み。INSERT は対象にしない。 */
  private static final Set<String> DAO_WRITES = Set.of(UPDATE, DELETE, "deleteById", "merge");

  /** 期待する jOOQ の実行時の版。上げるときは H1 の禁止の一覧を見直す。 */
  private static final String REVIEWED_JOOQ_VERSION = "3.21.7";

  /** 業務テーブルの UPDATE と DELETE は、TableWriter、LockedRoot、DeletedRoot だけが組み立てて実行する（H1）。 */
  @ArchTest
  /* package */ static final ArchRule tableWritesGoThroughTableWriter =
      tableWritesGoThroughTableWriterRule(BASE_PACKAGE);

  /**
   * {@code Jooq<Aggregate>Repository} の、集約ルートを受け取る {@code add} 以外のメソッドは、版を比べる入口と集約ルートの {@code
   * lockNo()} を呼ぶ（H3）。
   */
  @ArchTest
  /* package */ static final ArchRule repositoryUpdateAndDeleteCheckVersion =
      repositoryUpdateAndDeleteCheckVersionRule(BASE_PACKAGE);

  /**
   * Repository の {@code add}、{@code update}、{@code delete} は、{@code long lockNo()}
   * を持つ集約ルートを受け取る（H6）。
   */
  @ArchTest
  /* package */ static final ArchRule repositoryWritesTakeVersionedAggregates =
      repositoryWritesTakeVersionedAggregatesRule();

  /**
   * {@code VersionedCommand} を受け取る CommandHandler の {@code handle} は、その中で直接 {@code
   * ensureLockNo(ExpectedLockNo)} を呼ぶ（R4、旧 H4）。
   */
  @ArchTest
  /* package */ static final ArchRule commandHandlersEnsureScreenLockNo =
      commandHandlersEnsureScreenLockNoRule(BASE_PACKAGE);

  /**
   * presentation がコンストラクタか static factory で作る Command で保存する CommandHandler は、その Command を {@code
   * VersionedCommand} にする（R1）。
   */
  @ArchTest
  /* package */ static final ArchRule commandsBuiltByPresentationForWritesAreVersioned =
      commandsBuiltByPresentationForWritesAreVersionedRule(BASE_PACKAGE);

  /** Repository の {@code add} 以外の書き込みは CommandHandler だけが呼ぶ（R2）。 */
  @ArchTest
  /* package */ static final ArchRule onlyCommandHandlersUpdateOrDeleteAggregates =
      onlyCommandHandlersUpdateOrDeleteAggregatesRule();

  /** {@code ExpectedLockNo} は {@code presentation.web} の Request だけが作る（R3）。 */
  @ArchTest
  /* package */ static final ArchRule expectedLockNoIsCreatedOnlyByRequests =
      expectedLockNoIsCreatedOnlyByRequestsRule(BASE_PACKAGE);

  /** 集約ルートを受け取るメソッドは、版を比べない入口を呼ばない（H5）。 */
  @ArchTest
  /* package */ static final ArchRule aggregateMethodsDoNotUseUnversionedWrites =
      aggregateMethodsDoNotUseUnversionedWritesRule(BASE_PACKAGE);

  @Test
  @DisplayName("jOOQ の実行時の版は、書き込みの禁止の一覧を見直した版である")
  void jooqVersionIsReviewed() {
    assertThat(Constants.VERSION)
        .as(
            "jOOQ の版が変わった。docs/database/jooq-usage.md の「jOOQの版を上げるとき」に従い、"
                + "新しい書き込みの API を tableWritesGoThroughTableWriter の禁止の一覧に足してから、"
                + "REVIEWED_JOOQ_VERSION を更新する")
        .isEqualTo(REVIEWED_JOOQ_VERSION);
  }

  @Test
  @DisplayName("版を比べない入口は @CheckReturnValue を持ち、件数を捨てるとコンパイルで失敗する")
  void unversionedWritesRequireUsingTheCount() {
    // 注釈の型はテストの実行時のクラスパスにないため、リフレクションではなくバイトコードから読む。
    final List<String> annotated =
        new ClassFileImporter()
            .importClasses(TableWriter.class).get(TableWriter.class).getMethods().stream()
                .filter(method -> UNVERSIONED_WRITES.contains(method.getName()))
                .filter(
                    method ->
                        method.isAnnotatedWith(
                            "com.google.errorprone.annotations.CheckReturnValue"))
                .map(JavaMethod::getName)
                .toList();

    assertThat(annotated).containsExactlyInAnyOrderElementsOf(UNVERSIONED_WRITES);
  }

  /** H1 の規則を組み立てる。例外は基底パッケージの shared にある 3 クラスだけにする。 */
  /* package */ static ArchRule tableWritesGoThroughTableWriterRule(final String basePackage) {
    final String writerPackage = basePackage + WRITER_PACKAGE;
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(writerPackage + "TableWriter")
        .and()
        .doNotHaveFullyQualifiedName(writerPackage + "LockedRoot")
        .and()
        .doNotHaveFullyQualifiedName(writerPackage + "DeletedRoot")
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "jOOQ の UPDATE、DELETE、UPSERT、MERGE、Query 型の実行、問い合わせのモデルの $ で始まる API、"
                    + "UpdatableRecord と DAO の書き込み、Spring JDBC と JDBC の直接の利用、"
                    + "DataSource、Connection、ConnectionProvider を引数に取る呼び出し",
                TableWriterArchTest::isDirectWrite))
        .because(
            "版の条件、版の設定、件数の判定を書き忘れた UPDATE と DELETE は成功して、他の人の更新を黙って上書きするため。"
                + "直し方：業務テーブルの UPDATE と DELETE は shared の TableWriter で書く。"
                + "期待する版を持つなら updateCheckingVersion と deleteCheckingVersion、"
                + "持たないなら updateWhere と deleteWhere、子の行は戻り値の LockedRoot と DeletedRoot を使う。"
                + "規約："
                + DOCS);
  }

  /** H3 の規則を組み立てる。 */
  /* package */ static ArchRule repositoryUpdateAndDeleteCheckVersionRule(
      final String basePackage) {
    final String writer = basePackage + WRITER_PACKAGE + "TableWriter";
    return methods()
        .that(
            DescribedPredicate.describe(
                "Jooq*Repository の、集約ルートを受け取る add 以外の public メソッド",
                TableWriterArchTest::isRepositoryAggregateWrite))
        .should(callTheVersionedEntryPoint(writer))
        .allowEmptyShould(true)
        .because(
            "集約ルートの保存と削除で版を比べないと、古い画面や古い集約の保存が他の人の更新を上書きするため。"
                + "直し方：集約ルートを受け取るメソッドは add、update、delete だけにし、"
                + "update は TableWriter.updateCheckingVersion を、delete は deleteCheckingVersion を、"
                + "そのメソッドの中で直接呼ぶ（別のメソッドやラムダを経由しない）。"
                + "期待する版には、テーブルから読み直した値ではなく、引数の集約ルートの lockNo() を渡す。"
                + "規約：docs/backend/class-roles/jooq-repository.md、"
                + DOCS);
  }

  /** H6 の規則を組み立てる。 */
  /* package */ static ArchRule repositoryWritesTakeVersionedAggregatesRule() {
    return methods()
        .that(
            DescribedPredicate.describe(
                "domain.model の *Repository インタフェースの add、update、delete",
                TableWriterArchTest::isRepositoryInterfaceWrite))
        .should(takeOneAggregateRootWithLockNo())
        .allowEmptyShould(true)
        .because(
            "集約ルートが long lockNo() を持たないと、版を比べる規則がその集約を見つけられず、版を比べない保存が検出されないため。"
                + "直し方：add、update、delete は集約ルートを一つだけ受け取り、集約ルートは private final long lockNo と"
                + "引数のない long lockNo() を持つ。"
                + "規約：docs/backend/class-roles/repository.md、docs/backend/class-roles/aggregate.md、"
                + DOCS);
  }

  /** R4（旧 H4）の規則を組み立てる。 */
  /* package */ static ArchRule commandHandlersEnsureScreenLockNoRule(final String basePackage) {
    final String versionedCommand = basePackage + CONCURRENCY_PACKAGE + "VersionedCommand";
    final String expectedLockNo = basePackage + CONCURRENCY_PACKAGE + "ExpectedLockNo";
    return classes()
        .that()
        .haveSimpleNameEndingWith(COMMAND_HANDLER)
        .and(
            DescribedPredicate.describe(
                "handle の引数の Command が VersionedCommand である",
                type -> handlesCommandAssignableTo(type, versionedCommand)))
        .should(callEnsureLockNoInHandle(versionedCommand, expectedLockNo))
        .allowEmptyShould(true)
        .because(
            "画面の版を比べないと、別の人が先に更新した集約に対して業務の検査や外部の呼び出しを始めてしまうため。"
                + "直し方：handle で集約を取り出した直後に、handle の中で直接、集約の ensureLockNo(command.expectedLockNo()) を呼ぶ。"
                + "別のメソッドやラムダの中の呼び出しと、引数が long の ensureLockNo では規則を満たさない。"
                + "規約：docs/backend/class-roles/command-handler.md、"
                + DOCS);
  }

  /** R1 の規則を組み立てる。 */
  /* package */ static ArchRule commandsBuiltByPresentationForWritesAreVersionedRule(
      final String basePackage) {
    final String versionedCommand = basePackage + CONCURRENCY_PACKAGE + "VersionedCommand";
    return classes()
        .that()
        .haveSimpleNameEndingWith(COMMAND_HANDLER)
        .and(
            DescribedPredicate.describe(
                "presentation がコンストラクタ、コンストラクタ参照、Command 自身を返す static factory の呼び出しかメソッド参照で作る"
                    + " Command を受け取り、Repository の add 以外の書き込みを呼ぶ",
                TableWriterArchTest::savesCommandBuiltByPresentation))
        .should(takeVersionedCommand(versionedCommand))
        .allowEmptyShould(true)
        .because(
            "presentation が作る Command が版を持たないと、画面の版を比べずに保存する更新が通るため。"
                + "直し方：Command を VersionedCommand にして ExpectedLockNo を持たせ、Request の toCommand で作る。"
                + "コンストラクタではなく Command の static factory で作っても同じ規則の対象になる。"
                + "Listener が作る Command は VersionedCommand にしない。"
                + "Request と Listener の両方が作る Command は、別々の Command に分ける。"
                + "規約：docs/backend/class-roles/command.md、docs/backend/class-roles/request.md、"
                + DOCS);
  }

  /** R2 の規則を組み立てる。 */
  /* package */ static ArchRule onlyCommandHandlersUpdateOrDeleteAggregatesRule() {
    return noClasses()
        .that()
        .haveSimpleNameNotEndingWith(COMMAND_HANDLER)
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "domain.model の Repository の add 以外の書き込み（update、delete、集約ルートを受け取るメソッド）",
                TableWriterArchTest::isRepositoryWrite))
        .because(
            "Domain Service、QueryService、Repository の実装からの保存は ensureLockNo の検査を通らないため。"
                + "直し方：Repository の add 以外の書き込みは CommandHandler から呼び、"
                + "Repository の実装から別の Repository を呼ばない。"
                + "業務の規則は Domain Service に残し、保存は CommandHandler が行う。"
                + "規約：docs/backend/class-roles/repository.md、docs/backend/class-roles/command-handler.md、"
                + DOCS);
  }

  /** R3 の規則を組み立てる。 */
  /* package */ static ArchRule expectedLockNoIsCreatedOnlyByRequestsRule(
      final String basePackage) {
    final String expectedLockNo = basePackage + CONCURRENCY_PACKAGE + "ExpectedLockNo";
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "presentation.web の Request ではない",
                type ->
                    !(type.getPackageName().contains(".presentation.web")
                        && type.getSimpleName().endsWith("Request"))))
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "ExpectedLockNo のコンストラクタ（ExpectedLockNo::new を含む）",
                access ->
                    access.getTargetOwner().getFullName().equals(expectedLockNo)
                        && "<init>".equals(access.getName())))
        .because(
            "期待する版をコードで作ると、読んだ版を自分で渡して比較が常に成り立つため。"
                + "直し方：ExpectedLockNo は presentation.web の Request の toCommand だけで作り、"
                + "Repository と TableWriter には集約ルートの lockNo() を渡す。"
                + "規約：docs/backend/class-roles/request.md、docs/backend/class-roles/command.md、"
                + DOCS);
  }

  /** H5 の規則を組み立てる。 */
  /* package */ static ArchRule aggregateMethodsDoNotUseUnversionedWritesRule(
      final String basePackage) {
    final String writer = basePackage + WRITER_PACKAGE + "TableWriter";
    return classes()
        .should(notUseUnversionedWritesWithAggregateRoots(writer))
        .because(
            "集約ルートを持つ書き込みに版を比べない入口を使うと、版は進むが古い集約の保存を止めないため。"
                + "直し方：集約ルートを受け取るメソッドでは updateCheckingVersion か deleteCheckingVersion を使う。"
                + "updateWhere と deleteWhere は、識別子や条件だけを受け取るメソッドで使う。"
                + "規約："
                + DOCS);
  }

  /** 呼び出し先が、TableWriter を通さない業務テーブルの書き込みかを返す。 */
  private static boolean isDirectWrite(final JavaAccess<?> access) {
    final JavaClass owner = access.getTargetOwner();
    final String name = access.getName();
    final Map<Class<?>, Set<String>> namedWrites =
        Map.of(
            DSLContext.class, DSL_WRITES,
            DSL.class, DSL_WRITES,
            WithStep.class, DSL_WRITES,
            InsertOnDuplicateStep.class, UPSERTS,
            InsertQuery.class, UPSERTS,
            LoaderOptionsStep.class, Set.of("onDuplicateKeyUpdate"),
            UpdatableRecord.class, RECORD_WRITES,
            DAO.class, DAO_WRITES,
            DataSource.class, Set.of("getConnection"),
            ConnectionProvider.class, Set.of("acquire"));
    final boolean namedWrite =
        namedWrites.entrySet().stream()
            .anyMatch(
                entry -> owner.isAssignableTo(entry.getKey()) && entry.getValue().contains(name));
    final boolean execution =
        (owner.isAssignableTo(Update.class)
                || owner.isAssignableTo(Delete.class)
                || GENERIC_QUERY_TYPES.contains(owner.getFullName()))
            && (QUERY_EXECUTIONS.contains(name) || name.startsWith("fetch"));
    return namedWrite
        || execution
        || owner.isAssignableTo(Merge.class)
        || owner.isAssignableTo(Connection.class)
        || owner.isAssignableTo(Statement.class)
        // ResourceDatabasePopulator や ScriptUtils も任意の SQL を流せるため、サブパッケージを選ばずに禁じる。
        || owner.getPackageName().startsWith("org.springframework.jdbc")
        // 問い合わせのモデルの API（QOM の $onDuplicateKeyUpdate、$replace など）は、INSERT を UPSERT に組み替えられる。
        || (owner.getPackageName().startsWith("org.jooq") && name.startsWith("$"))
        || takesConnectionSource(access);
  }

  /**
   * 呼び出し先が {@code DataSource}、{@code Connection}、{@code ConnectionProvider} を引数に取るかを返す。
   *
   * <p>接続の元を受け取るライブラリ（Spring Boot の {@code DataSourceScriptDatabaseInitializer}、{@code
   * DSL.using(Connection)} など）は、パッケージを選ばずに任意の SQL を流せるため、引数の型で禁じる。
   */
  private static boolean takesConnectionSource(final JavaAccess<?> access) {
    return access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
        && target.getRawParameterTypes().stream()
            .anyMatch(
                type ->
                    type.isAssignableTo(DataSource.class)
                        || type.isAssignableTo(Connection.class)
                        || type.isAssignableTo(ConnectionProvider.class));
  }

  /**
   * {@code Jooq*Repository} の、集約ルートを受け取る {@code add} 以外の public メソッドかを返す。
   *
   * <p>{@code update} と {@code delete} の名前に限らないため、{@code save(Order)} のような名前で規則を外れない。
   */
  private static boolean isRepositoryAggregateWrite(final JavaMethod method) {
    final JavaClass owner = method.getOwner();
    return method.getModifiers().contains(JavaModifier.PUBLIC)
        && !method.getModifiers().contains(JavaModifier.SYNTHETIC)
        && owner.getSimpleName().startsWith("Jooq")
        && owner.getSimpleName().endsWith("Repository")
        && owner.getPackageName().contains(".infrastructure.persistence")
        && isAggregateWrite(method.getName(), method.getRawParameterTypes());
  }

  private static ArchCondition<JavaMethod> callTheVersionedEntryPoint(final String writer) {
    return new ArchCondition<>(
        "call TableWriter.updateCheckingVersion or deleteCheckingVersion and the aggregate's lockNo()") {
      @Override
      public void check(final JavaMethod item, final ConditionEvents events) {
        final Set<String> expected =
            switch (item.getName()) {
              case UPDATE -> Set.of(UPDATE_CHECKING_VERSION);
              case DELETE -> Set.of(DELETE_CHECKING_VERSION);
              default -> Set.of(UPDATE_CHECKING_VERSION, DELETE_CHECKING_VERSION);
            };
        final boolean calls =
            item.getMethodCallsFromSelf().stream()
                .anyMatch(
                    call ->
                        call.getTargetOwner().getFullName().equals(writer)
                            && expected.contains(call.getName()));
        if (!calls) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getFullName()
                      + " が TableWriter の "
                      + String.join(" か ", new TreeSet<>(expected))
                      + " を直接呼んでいない。"));
        }
        // ponytail: 値の流れは追わず、lockNo() の呼び出しの有無だけを見る。読み直した版を渡しつつ lockNo() も呼ぶ実装は通る。
        final Set<String> aggregateRoots =
            item.getRawParameterTypes().stream()
                .filter(TableWriterArchTest::isAggregateRoot)
                .map(JavaClass::getFullName)
                .collect(Collectors.toSet());
        final boolean readsLockNo =
            item.getMethodCallsFromSelf().stream()
                .anyMatch(
                    call ->
                        "lockNo".equals(call.getName())
                            && aggregateRoots.contains(call.getTargetOwner().getFullName()));
        if (!readsLockNo) {
          events.add(
              SimpleConditionEvent.violated(
                  item, item.getFullName() + " が引数の集約ルートの lockNo() を呼んでいない。"));
        }
      }
    };
  }

  /**
   * {@code domain.model} の {@code *Repository} インタフェースの {@code add}、{@code update}、{@code delete}
   * かを返す。
   *
   * <p>必須の {@code add} を含めるため、{@code save(Order)} のような名前で保存する Repository でも、{@code lockNo()}
   * を持たない集約は {@code add} で検出される。
   */
  private static boolean isRepositoryInterfaceWrite(final JavaMethod method) {
    final JavaClass owner = method.getOwner();
    return Set.of(ADD, UPDATE, DELETE).contains(method.getName())
        && owner.isInterface()
        && owner.getSimpleName().endsWith("Repository")
        && owner.getPackageName().contains(DOMAIN_MODEL);
  }

  private static ArchCondition<JavaMethod> takeOneAggregateRootWithLockNo() {
    return new ArchCondition<>("take exactly one domain.model type declaring long lockNo()") {
      @Override
      public void check(final JavaMethod item, final ConditionEvents events) {
        final List<JavaClass> parameters = item.getRawParameterTypes();
        if (parameters.size() != 1 || !isAggregateRoot(parameters.getFirst())) {
          events.add(
              SimpleConditionEvent.violated(
                  item, item.getFullName() + " の引数が、引数のない long lockNo() を宣言する集約ルート一つではない。"));
        }
      }
    };
  }

  /** 単一引数の {@code handle} の引数の型（Command）を返す。 */
  private static Stream<JavaClass> handleParameters(final JavaClass type) {
    return type.getMethods().stream()
        .filter(method -> HANDLE.equals(method.getName()))
        .filter(method -> method.getRawParameterTypes().size() == 1)
        .map(method -> method.getRawParameterTypes().getFirst());
  }

  /** {@code handle} の引数の型が、指定した型に代入できるかを返す。 */
  private static boolean handlesCommandAssignableTo(final JavaClass type, final String typeName) {
    return handleParameters(type).anyMatch(command -> command.isAssignableTo(typeName));
  }

  /** {@code domain.model} の {@code *Repository} かを返す。 */
  private static boolean isDomainRepository(final JavaClass type) {
    return type.getSimpleName().endsWith(REPOSITORY)
        && type.getPackageName().contains(DOMAIN_MODEL);
  }

  /** {@code add} 以外で、集約ルートを受け取るメソッドかを返す。R1、R2、H3 が同じ判定で書き込みを見つける。 */
  private static boolean isAggregateWrite(final String name, final List<JavaClass> parameterTypes) {
    return !ADD.equals(name)
        && parameterTypes.stream().anyMatch(TableWriterArchTest::isAggregateRoot);
  }

  /**
   * 呼び出し先が、{@code domain.model} の Repository の {@code add} 以外の書き込みかを返す。
   *
   * <p>{@code save} のような別名でも、集約ルートを受け取れば書き込みとみなす。 {@code update} と {@code delete} は H6
   * が集約ルートを受け取らせるため、誤って識別子を受け取る形でも名前で書き込みとみなす。
   */
  private static boolean isRepositoryWrite(final JavaAccess<?> access) {
    final String name = access.getName();
    return isDomainRepository(access.getTargetOwner())
        && (UPDATE.equals(name)
            || DELETE.equals(name)
            || (access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                && isAggregateWrite(name, target.getRawParameterTypes())));
  }

  /** R1 の対象かを返す。CommandHandler が Repository で保存し、その Command を presentation が作る。 */
  private static boolean savesCommandBuiltByPresentation(final JavaClass handler) {
    // ponytail: handle の引数の型と、presentation が作る Command の型を突き合わせるだけで、値の流れは追わない。
    return handler.getAccessesFromSelf().stream().anyMatch(TableWriterArchTest::isRepositoryWrite)
        && handleParameters(handler).anyMatch(TableWriterArchTest::isBuiltByPresentation);
  }

  /** コンストラクタ、コンストラクタ参照、Command 自身を返す static factory の呼び出しとメソッド参照の、どれかの呼び出し元が presentation かを返す。 */
  private static boolean isBuiltByPresentation(final JavaClass command) {
    final Stream<JavaAccess<?>> factories =
        command.getMethods().stream()
            .filter(method -> method.getModifiers().contains(JavaModifier.STATIC))
            .filter(method -> method.getRawReturnType().isAssignableTo(command.getName()))
            .flatMap(
                method ->
                    Stream.concat(
                        method.getCallsOfSelf().stream(), method.getReferencesToSelf().stream()));
    return Stream.<JavaAccess<?>>concat(
            Stream.concat(
                command.getConstructorCallsToSelf().stream(),
                command.getConstructorReferencesToSelf().stream()),
            factories)
        .anyMatch(access -> access.getOriginOwner().getPackageName().contains(".presentation."));
  }

  private static ArchCondition<JavaClass> takeVersionedCommand(final String versionedCommand) {
    return new ArchCondition<>("take a Command assignable to VersionedCommand") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        handleParameters(item)
            .filter(command -> !command.isAssignableTo(versionedCommand))
            .forEach(
                command ->
                    events.add(
                        SimpleConditionEvent.violated(
                            item,
                            item.getFullName()
                                + " が受け取る "
                                + command.getFullName()
                                + " は presentation が作るが VersionedCommand ではない。")));
      }
    };
  }

  private static ArchCondition<JavaClass> callEnsureLockNoInHandle(
      final String versionedCommand, final String expectedLockNo) {
    return new ArchCondition<>(
        "call ensureLockNo(ExpectedLockNo) on a domain.model type in handle") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        // handle 自身の呼び出しだけを見るため、別のメソッドやラムダの本体の呼び出しでは満たせない。
        // 引数の型まで見るため、ensureLockNo(long) のオーバーロードでも満たせない。
        item.getMethods().stream()
            .filter(method -> HANDLE.equals(method.getName()))
            .filter(method -> method.getRawParameterTypes().size() == 1)
            .filter(
                method -> method.getRawParameterTypes().getFirst().isAssignableTo(versionedCommand))
            .filter(
                method ->
                    method.getMethodCallsFromSelf().stream()
                        .noneMatch(
                            call ->
                                ENSURE_LOCK_NO.equals(call.getName())
                                    && call.getTargetOwner().getPackageName().contains(DOMAIN_MODEL)
                                    && call.getTarget().getRawParameterTypes().stream()
                                        .anyMatch(
                                            type -> type.getFullName().equals(expectedLockNo))))
            .forEach(
                method ->
                    events.add(
                        SimpleConditionEvent.violated(
                            item,
                            method.getFullName()
                                + " が集約の ensureLockNo(ExpectedLockNo) を handle の中で直接呼んでいない。")));
      }
    };
  }

  private static ArchCondition<JavaClass> notUseUnversionedWritesWithAggregateRoots(
      final String writer) {
    return new ArchCondition<>(
        "not call TableWriter.updateWhere or deleteWhere from code units taking an aggregate root") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        // ラムダは捕捉した変数を引数に持つ合成メソッドになるため、ラムダの中の呼び出しも同じ判定で見る。
        for (final JavaCodeUnit codeUnit : item.getCodeUnits()) {
          final boolean callsUnversioned =
              codeUnit.getAccessesFromSelf().stream()
                  .anyMatch(
                      access ->
                          access.getTargetOwner().getFullName().equals(writer)
                              && UNVERSIONED_WRITES.contains(access.getName()));
          if (callsUnversioned
              && codeUnit.getRawParameterTypes().stream()
                  .anyMatch(TableWriterArchTest::isAggregateRoot)) {
            events.add(
                SimpleConditionEvent.violated(
                    item, codeUnit.getFullName() + " は集約ルートを受け取り、TableWriter の版を比べない入口を呼ぶ。"));
          }
        }
      }
    };
  }

  /** {@code domain.model} にあり、引数のない {@code long lockNo()} を宣言する型かを返す。 */
  private static boolean isAggregateRoot(final JavaClass type) {
    return type.getPackageName().contains(DOMAIN_MODEL)
        && type.getMethods().stream()
            .anyMatch(
                method ->
                    "lockNo".equals(method.getName())
                        && method.getRawParameterTypes().isEmpty()
                        && method.getRawReturnType().isEquivalentTo(long.class));
  }
}
