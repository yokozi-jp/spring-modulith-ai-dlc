package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
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
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.jooq.TableRecord;
import org.jooq.Update;
import org.jooq.WithStep;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 集約の INSERT と UPDATE の入口を {@code TableWriter} に集め、その他の書き込みを禁じて、楽観的ロックの版の比べ忘れを止める（ADR-054、
 * docs/database/postgresql-concurrency-control.md）。
 *
 * <p>基底パッケージを含む規則は {@code xxxRule(basePackage)} のファクトリで組み立て、{@link ArchitectureRuleFixtureTest}
 * がフィクスチャのパッケージで同じ規則を再利用する。
 *
 * <p>集約ルートは、{@code domain.model} にあり、{@code shared.concurrency} の {@code Versioned} を実装する型とする。
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
 *       の {@code handle} は、その中か、そこから呼ぶ private メソッドの中で直接 {@code ensureLockNo(ExpectedLockNo)} を呼ぶ。
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
  private static final String WRITER_PACKAGE = ".shared.persistence.";

  /** 古い出版を FAILED に戻す 1 文の UPDATE を持つ、H1 の例外の型（基底パッケージからの完全修飾型名）。 */
  private static final String STALE_PUBLICATIONS = ".shared.eventresubmission.StalePublications";

  /** 楽観的ロックの語彙を置く shared のパッケージ（基底パッケージからの相対）。 */
  private static final String CONCURRENCY_PACKAGE = ".shared.concurrency.";

  /** 集約と Repository を置くパッケージ。 */
  private static final String DOMAIN_MODEL = ".domain.model";

  /** 集約ルートの印の名前。 */
  private static final String VERSIONED = "Versioned";

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

  /** 楽観的ロックの規約と、この決定の ADR。 */
  private static final String DOCS =
      "docs/database/postgresql-concurrency-control.md、"
          + "docs/adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md";

  /**
   * {@code DSLContext}、{@code DSL}、{@code WithStep} の、INSERT、UPDATE、DELETE、MERGE の入口と接続の直接の利用。
   *
   * <p>{@code Insert}、{@code Update}、{@code Delete} を作る入口をすべて禁じるため、{@code batch} や {@code
   * subscribe} のような問い合わせを受け取る実行の API は禁じなくてよい。
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
          "executeInsert",
          "executeUpdate",
          "executeDelete",
          "loadInto",
          "insertInto",
          "insertQuery",
          "batchInsert",
          "connection",
          "connectionResult");

  /**
   * {@code Update} と {@code Delete} に代入できる型の実行。名前が {@code fetch} で始まるものは、この一覧に持たず {@code
   * isDirectWrite} で見る。
   */
  /* package */ static final Set<String> QUERY_EXECUTIONS =
      Set.of("execute", "executeAsync", "returning", "returningResult");

  /**
   * {@code Update} と {@code Delete} が代入できる汎用の問い合わせの型。
   *
   * <p>メソッド参照（{@code Update::execute}）のバイトコードには {@code execute} を宣言した {@code Query}
   * が残るため、この型の実行も禁じる。
   */
  private static final Set<String> GENERIC_QUERY_TYPES =
      Set.of(Query.class.getName(), RowCountQuery.class.getName(), DMLQuery.class.getName());

  /**
   * {@code InsertOnDuplicateStep} と {@code InsertQuery} の UPSERT。
   *
   * <p>{@code insertInto} と {@code insertQuery} を禁じるため、本番のコードはこれらの型を作れない。入口の禁止を外したときに備える二重の防御である。
   */
  private static final Set<String> UPSERTS =
      Set.of(
          "onConflict",
          "onConflictOnConstraint",
          "onConflictWhere",
          "onDuplicateKeyUpdate",
          "addValueForUpdate",
          "addValuesForUpdate");

  /** {@code TableRecord} と {@code UpdatableRecord} の書き込み。 */
  private static final Set<String> RECORD_WRITES =
      Set.of("insert", "store", UPDATE, DELETE, "merge");

  /** {@code DAO} の書き込み。 */
  private static final Set<String> DAO_WRITES =
      Set.of("insert", UPDATE, DELETE, "deleteById", "merge");

  /** 型ごとの、禁じるメソッドの名前。型の下位の型も対象にする。 */
  /* package */ static final Map<Class<?>, Set<String>> NAMED_WRITES =
      Map.of(
          DSLContext.class, DSL_WRITES,
          DSL.class, DSL_WRITES,
          WithStep.class, DSL_WRITES,
          InsertOnDuplicateStep.class, UPSERTS,
          InsertQuery.class, UPSERTS,
          LoaderOptionsStep.class, Set.of("onDuplicateKeyUpdate"),
          TableRecord.class, RECORD_WRITES,
          DAO.class, DAO_WRITES,
          DataSource.class, Set.of("getConnection"),
          ConnectionProvider.class, Set.of("acquire"));

  /** 期待する jOOQ の実行時の版。上げるときは H1 の禁止の一覧を見直す。 */
  private static final String REVIEWED_JOOQ_VERSION = "3.21.9";

  /**
   * 業務テーブルの INSERT、UPDATE、DELETE は、TableWriter とその子の行の書き込み（ChildWriter）、{@code Idempotency-Key}
   * の行の書き込み（IdempotencyKeyWriter）だけが組み立てて実行する（H1）。
   *
   * <p>トランザクションの初めにモジュールのロールとテナントを設定する ModuleRoleTransactionManager は、業務テーブルに触れず、設定の名前を固定した 1 つの
   * {@code set_config} の文だけを実行するため対象にしない（ADR-094、ADR-107）。
   *
   * <p>落ちたまま残った出版を FAILED に戻す StalePublications は、Spring Modulith の {@code
   * modulith.event_publication} だけを 1 文の UPDATE で更新し、業務テーブルに触れないため対象にしない（ADR-079）。
   */
  @ArchTest
  /* package */ static final ArchRule tableWritesGoThroughTableWriter =
      tableWritesGoThroughTableWriterRule(BASE_PACKAGE);

  /**
   * {@code Jooq<Aggregate>Repository} の、集約ルートを受け取る {@code add} 以外のメソッドは、版を比べる入口 {@code
   * TableWriter.update} を呼ぶ（H3）。版は {@code Versioned} の集約ルートから {@code TableWriter} が読む。
   */
  @ArchTest
  /* package */ static final ArchRule repositoryUpdateCallsVersionedEntryPoint =
      repositoryUpdateCallsVersionedEntryPointRule(BASE_PACKAGE);

  /**
   * Repository の {@code add}、{@code update}、{@code delete} は、{@code Versioned} を持つ集約ルートを受け取る（H6）。
   */
  @ArchTest
  /* package */ static final ArchRule repositoryWritesTakeVersionedAggregates =
      repositoryWritesTakeVersionedAggregatesRule();

  /**
   * {@code VersionedCommand} を受け取る CommandHandler の {@code handle} は、その中か、そこから呼ぶ private メソッドの中で直接
   * {@code ensureLockNo(ExpectedLockNo)} を呼ぶ（R4、旧 H4）。
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

  /**
   * H1 の規則を組み立てる。例外は基底パッケージの shared にある TableWriter、ChildWriter、IdempotencyKeyWriter、
   * ModuleRoleTransactionManager と、shared.eventresubmission の StalePublications だけにする。
   */
  /* package */ static ArchRule tableWritesGoThroughTableWriterRule(final String basePackage) {
    final String writerPackage = basePackage + WRITER_PACKAGE;
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(writerPackage + "TableWriter")
        .and()
        .doNotHaveFullyQualifiedName(writerPackage + "ChildWriter")
        .and()
        .doNotHaveFullyQualifiedName(writerPackage + "IdempotencyKeyWriter")
        .and()
        .doNotHaveFullyQualifiedName(writerPackage + "ModuleRoleTransactionManager")
        .and()
        .doNotHaveFullyQualifiedName(writerPackage + "ModuleRoleTransactionManager$Config")
        .and()
        .doNotHaveFullyQualifiedName(basePackage + STALE_PUBLICATIONS)
        .should()
        .accessTargetWhere(
            DescribedPredicate.describe(
                "jOOQ の INSERT、UPDATE、DELETE、UPSERT、MERGE、Query 型の実行、問い合わせのモデルの $ で始まる API、"
                    + "TableRecord、UpdatableRecord、DAO の書き込み、Spring JDBC と JDBC の直接の利用、"
                    + "DataSource、Connection、ConnectionProvider を引数に取る呼び出し",
                TableWriterArchTest::isDirectWrite))
        .because(
            "版の条件、版の設定、件数の判定を書き忘れた UPDATE は成功して、他の人の更新を黙って上書きし、"
                + "TableWriter を通さない INSERT は一意制約の違反（23505）を ConflictException に変えず 500 を返すため。"
                + "直し方：業務テーブルの INSERT と UPDATE は shared の TableWriter の insert と update で書く。"
                + "集約の形は AggregateTable と ChildTable で宣言し、子の行は ChildRows で渡す。"
                + "規約："
                + DOCS);
  }

  /** H3 の規則を組み立てる。 */
  /* package */ static ArchRule repositoryUpdateCallsVersionedEntryPointRule(
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
                + "直し方：集約ルートを受け取る public メソッドは add と update だけにし、"
                + "update は TableWriter.update を、そのメソッドの中で直接呼ぶ（別のメソッドを経由しない）。"
                + "delete と版を比べない更新の入口は TableWriter にないため、ADR-054 を更新して足す。"
                + "TableWriter.update は集約ルート（Versioned）を受け取り、その lockNo() を期待する版にする。"
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
            "集約ルートが Versioned を実装しないと、版を比べる規則がその集約を見つけられず、TableWriter.update にも渡せないため。"
                + "直し方：add、update、delete は集約ルートを一つだけ受け取り、集約ルートは private final long lockNo を持ち、"
                + "shared.concurrency の Versioned を実装する。"
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
                + "直し方：handle で集約を取り出した直後に、handle か handle が呼ぶ private メソッドの中で直接、"
                + "集約の ensureLockNo(command.expectedLockNo()) を呼ぶ。"
                + "handle から呼ばないメソッドの中の呼び出しと、引数が long の ensureLockNo では規則を満たさない。"
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
                + "TableWriter には集約ルートを渡す。"
                + "規約：docs/backend/class-roles/request.md、docs/backend/class-roles/command.md、"
                + DOCS);
  }

  /** 呼び出し先が、TableWriter を通さない業務テーブルの書き込みかを返す。 */
  private static boolean isDirectWrite(final JavaAccess<?> access) {
    final JavaClass owner = access.getTargetOwner();
    final String name = access.getName();
    final boolean namedWrite =
        NAMED_WRITES.entrySet().stream()
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
            .anyMatch(type ->
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
        "be update and call TableWriter.update, the entry point that compares the version") {
      @Override
      public void check(final JavaMethod item, final ConditionEvents events) {
        if (!UPDATE.equals(item.getName())) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getFullName()
                      + " は集約ルートを受け取る add 以外のメソッドだが、update ではない。"
                      + "TableWriter には update の入口しかない。別の書き込みが要るなら、ADR-054 を更新して入口を足す。"));
          return;
        }
        // 版は TableWriter.update の集約ルートの引数から読むため、呼び出しの有無だけを見る。
        final boolean calls =
            item.getMethodCallsFromSelf().stream()
                .anyMatch(call ->
                    call.getTargetOwner().getFullName().equals(writer)
                        && UPDATE.equals(call.getName()));
        if (!calls) {
          events.add(
              SimpleConditionEvent.violated(
                  item, item.getFullName() + " が TableWriter の update を直接呼んでいない。"));
        }
      }
    };
  }

  /**
   * {@code domain.model} の {@code *Repository} インタフェースの {@code add}、{@code update}、{@code delete}
   * かを返す。
   *
   * <p>必須の {@code add} を含めるため、{@code save(Order)} のような名前で保存する Repository でも、{@code Versioned}
   * を実装しない集約は {@code add} で検出される。
   */
  private static boolean isRepositoryInterfaceWrite(final JavaMethod method) {
    final JavaClass owner = method.getOwner();
    return Set.of(ADD, UPDATE, DELETE).contains(method.getName())
        && owner.isInterface()
        && owner.getSimpleName().endsWith("Repository")
        && owner.getPackageName().contains(DOMAIN_MODEL);
  }

  private static ArchCondition<JavaMethod> takeOneAggregateRootWithLockNo() {
    return new ArchCondition<>("take exactly one domain.model type implementing Versioned") {
      @Override
      public void check(final JavaMethod item, final ConditionEvents events) {
        final List<JavaClass> parameters = item.getRawParameterTypes();
        if (parameters.size() != 1 || !isAggregateRoot(parameters.getFirst())) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getFullName() + " の引数が、shared.concurrency の Versioned を実装する集約ルート一つではない。"));
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
            .flatMap(method ->
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
            .forEach(command ->
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
        // handle と、handle が呼ぶ同じクラスの private メソッドの呼び出しだけを見るため、handle から呼ばないメソッドでは満たせない。
        // ラムダの本体の呼び出しは handle の呼び出しとして数えられる。
        // 引数の型まで見るため、ensureLockNo(long) のオーバーロードでも満たせない。
        item.getMethods().stream()
            .filter(method -> HANDLE.equals(method.getName()))
            .filter(method -> method.getRawParameterTypes().size() == 1)
            .filter(
                method -> method.getRawParameterTypes().getFirst().isAssignableTo(versionedCommand))
            .filter(method ->
                callsFromHandle(method)
                    .noneMatch(call ->
                        ENSURE_LOCK_NO.equals(call.getName())
                            && call.getTargetOwner().getPackageName().contains(DOMAIN_MODEL)
                            && call.getTarget().getRawParameterTypes().stream()
                                .anyMatch(type -> type.getFullName().equals(expectedLockNo))))
            .forEach(method ->
                events.add(
                    SimpleConditionEvent.violated(
                        item,
                        method.getFullName()
                            + " が集約の ensureLockNo(ExpectedLockNo) を、handle か handle が呼ぶ private"
                            + " メソッドの中で直接呼んでいない。")));
      }
    };
  }

  /**
   * {@code handle} の呼び出しと、{@code handle} が呼ぶ同じクラスの private メソッドの呼び出しを返す。
   *
   * <p>{@code idempotency.execute(command, Result.class, () -> confirm(command))} のように、本体を private
   * メソッドに置いた {@code handle} の本体の呼び出しも数えるためである（ADR-126）。
   */
  private static Stream<JavaMethodCall> callsFromHandle(final JavaMethod handle) {
    return Stream.concat(
        handle.getMethodCallsFromSelf().stream(),
        handle.getMethodCallsFromSelf().stream()
            .filter(call -> call.getTargetOwner().equals(handle.getOwner()))
            .flatMap(call -> call.getTarget().resolveMember().stream())
            .filter(target -> target.getModifiers().contains(JavaModifier.PRIVATE))
            .flatMap(target -> target.getMethodCallsFromSelf().stream()));
  }

  /** {@code domain.model} にあり、{@code shared.concurrency} の {@code Versioned} を実装する型かを返す。 */
  private static boolean isAggregateRoot(final JavaClass type) {
    return type.getPackageName().contains(DOMAIN_MODEL)
        && type.getAllRawInterfaces().stream()
            .anyMatch(
                implemented -> implemented.getName().endsWith(CONCURRENCY_PACKAGE + VERSIONED));
  }
}
