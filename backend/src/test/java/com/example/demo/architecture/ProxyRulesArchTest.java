package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import com.example.demo.shared.security.NoRoleRequired;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.base.HasDescription;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.ProxyRules;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.prepost.PreFilter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

/** Spring と Resilience4j がアノテーションと Aspect で足す振る舞いが、黙って効かなくなる書き方を禁止する。 */
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
// 規則ごとに違反の文を組み立てる ArchCondition を持つため、規則の数だけメソッドが増える。
@SuppressWarnings("PMD.TooManyMethods")
class ProxyRulesArchTest {

  /** リポジトリのルート。Gradle はテストを backend で実行する。 */
  private static final Path REPOSITORY_ROOT = Path.of("..");

  /** Java の実装規約の、リポジトリのルートからのパス。 */
  private static final String JAVA_CODING_PATH = "docs/backend/java-coding.md";

  /** because の最後に置く、Java の実装規約への参照。 */
  private static final String JAVA_CODING_DOC = "規約：" + JAVA_CODING_PATH;

  /** because の最後に置く、層の責務の規約への参照。 */
  private static final String LAYERS_DOC = "規約：docs/backend/layers.md";

  /** 本番のコードの基底パッケージ。 */
  private static final String BASE_PACKAGE = DemoApplication.class.getPackageName();

  /** {@link #INTERCEPTED_ANNOTATIONS} と同じアノテーションを箇条書きにする、Java の実装規約の見出し。 */
  private static final String INTERCEPTED_HEADING = "## プロキシで動くアノテーション";

  /** 箇条書きに書いたアノテーションの名前。 */
  private static final Pattern ANNOTATION_NAME = Pattern.compile("`@(\\w+)`");

  /** pgm_cd を Aspect で渡すと決めた ADR のパス。 */
  private static final String ADR_051 =
      "docs/adr/ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md";

  /** pointcut の式を持つ AspectJ のアノテーション。 */
  private static final Set<Class<? extends Annotation>> POINTCUT_ANNOTATIONS =
      Set.of(
          Around.class,
          Before.class,
          After.class,
          AfterReturning.class,
          AfterThrowing.class,
          Pointcut.class);

  /** 役割と権限を確かめる Spring Security の method security のアノテーション。 */
  private static final List<Class<? extends Annotation>> METHOD_SECURITY_ANNOTATIONS =
      List.of(
          PreAuthorize.class,
          PostAuthorize.class,
          PreFilter.class,
          PostFilter.class,
          Secured.class);

  /**
   * Spring と Resilience4j のプロキシが呼び出しを横取りして動かすアノテーション。
   *
   * <p>{@code @ApplicationModuleListener} のように、これらをメタアノテーションに持つ合成アノテーションも対象にする。
   */
  private static final List<Class<? extends Annotation>> INTERCEPTED_ANNOTATIONS =
      Stream.of(
              List.<Class<? extends Annotation>>of(
                  Transactional.class,
                  Async.class,
                  Cacheable.class,
                  CachePut.class,
                  CacheEvict.class,
                  Retryable.class,
                  ConcurrencyLimit.class),
              METHOD_SECURITY_ANNOTATIONS,
              List.<Class<? extends Annotation>>of(
                  CircuitBreaker.class,
                  Retry.class,
                  RateLimiter.class,
                  Bulkhead.class,
                  TimeLimiter.class))
          .flatMap(List::stream)
          .toList();

  /** {@link #INTERCEPTED_ANNOTATIONS} のどれかを、直接かメタアノテーションとして持つメソッドと呼び出し先。 */
  private static final DescribedPredicate<CanBeAnnotated> INTERCEPTED =
      metaAnnotatedWithOneOf(INTERCEPTED_ANNOTATIONS);

  /** {@link #METHOD_SECURITY_ANNOTATIONS} のどれかを、直接かメタアノテーションとして持つクラスとメソッド。 */
  private static final DescribedPredicate<CanBeAnnotated> METHOD_SECURED =
      metaAnnotatedWithOneOf(METHOD_SECURITY_ANNOTATIONS);

  /** 役割を確かめるか、確かめないかの宣言を、直接かメタアノテーションとして持つクラスとメソッド。 */
  private static final DescribedPredicate<CanBeAnnotated> DECLARES_AUTHORIZATION =
      metaAnnotatedWithOneOf(List.of(PreAuthorize.class, NoRoleRequired.class));

  /** {@code @PreAuthorize} に書いてよい式。引用符で囲んだ役割か権限の名前を、1 つの関数に並べるだけにする。 */
  private static final Pattern ROLE_OR_AUTHORITY_NAMES =
      Pattern.compile(
          "(hasRole|hasAuthority)\\('[\\w:.-]+'\\)"
              + "|(hasAnyRole|hasAnyAuthority)\\('[\\w:.-]+'(\\s*,\\s*'[\\w:.-]+')*\\)");

  /** プロキシがメソッドを上書きできなくなる修飾子。 */
  private static final List<JavaModifier> NOT_OVERRIDABLE =
      List.of(JavaModifier.PRIVATE, JavaModifier.FINAL, JavaModifier.STATIC);

  /**
   * AspectJ の advice と {@code @Pointcut} の式に {@code within} を書き、対象のクラスを型の名前で絞る。
   *
   * <p>{@code execution} の宣言型だけで絞ると、AspectJ はすべての Bean のすべてのメソッドで上位型をたどって照合し、Spring
   * のコンテキストの起動が数倍遅くなる。
   */
  @ArchTest
  /* package */ static final ArchRule pointcutsNarrowTargetsWithWithin =
      methods()
          .that()
          .areAnnotatedWith(Around.class)
          .or()
          .areAnnotatedWith(Before.class)
          .or()
          .areAnnotatedWith(After.class)
          .or()
          .areAnnotatedWith(AfterReturning.class)
          .or()
          .areAnnotatedWith(AfterThrowing.class)
          .or()
          .areAnnotatedWith(Pointcut.class)
          .should(declarePointcutWithWithin())
          .allowEmptyShould(true)
          .because(
              "execution の宣言型だけで対象を絞ると、AspectJ がすべての Bean のすべてのメソッドで上位型をたどって照合し、"
                  + "Spring のコンテキストの起動が数倍遅くなるため。"
                  + "直し方：式に within(<パッケージ>..*<役割名>) を書き、"
                  + "execution にはメソッドの名前と修飾子だけを書く。"
                  + JAVA_CODING_DOC
                  + "、"
                  + ADR_051);

  /**
   * {@code @Aspect} のクラスに {@link Ordered#LOWEST_PRECEDENCE} より小さい {@code @Order} を付け、トランザクションの
   * advisor より外側で動かす。
   *
   * <p>値のない {@code @Order} は {@link Ordered#LOWEST_PRECEDENCE} になり、トランザクションの advisor と同じ順序になる。
   */
  @ArchTest
  /* package */ static final ArchRule aspectsDeclareOrder =
      classes()
          .that()
          .areAnnotatedWith(Aspect.class)
          .should(declareOrderOutsideTransaction())
          .allowEmptyShould(true)
          .because(
              "Spring は別々の Aspect とトランザクションが同じ順序の値で同じメソッドに掛かるときの前後を決めないため。"
                  + "直し方：Aspect のクラスに @Order(Ordered.LOWEST_PRECEDENCE - 100) のように、"
                  + "Ordered.LOWEST_PRECEDENCE より小さい値の @Order を付ける。"
                  + JAVA_CODING_DOC
                  + "、"
                  + ADR_051);

  /**
   * {@link EnableTransactionManagement} を宣言せず、トランザクションの advisor の順序を Spring の既定の {@link
   * Ordered#LOWEST_PRECEDENCE} に保つ。
   *
   * <p>{@link #aspectsDeclareOrder} は、トランザクションの advisor がこの順序にあることを前提にする。
   */
  @ArchTest
  /* package */ static final ArchRule transactionManagementIsNotRedeclared =
      noClasses()
          .should()
          .beAnnotatedWith(EnableTransactionManagement.class)
          .because(
              "トランザクションの advisor の順序が変わると、Aspect がトランザクションの内側で動いても"
                  + "aspectsDeclareOrder が検出できないため。"
                  + "直し方：@EnableTransactionManagement を消し、Spring Boot のトランザクションの自動構成に任せる。"
                  + JAVA_CODING_DOC
                  + "、"
                  + ADR_051);

  /**
   * プロキシで動くアノテーションを付けたメソッドを、同じクラスの中から直接呼ばない。
   *
   * <p>呼び出し先のメソッドのアノテーションを、メタアノテーションまでたどって見る。継承したメソッドは、宣言したクラスのアノテーションで判定する。
   */
  @ArchTest
  /* package */ static final ArchRule proxiedMethodsAreNotCalledFromSameClass =
      ProxyRules.no_classes_should_directly_call_other_methods_declared_in_the_same_class_that(
              INTERCEPTED)
          .because(
              "Spring の AOP プロキシは Bean の外からの呼び出しだけを横取りし、"
                  + "同じクラスの中からの呼び出しではアノテーションが黙って効かないため。"
                  + "直し方：アノテーションを付けたメソッドを同じクラスの中から呼ばず、別の Bean へ移して、その Bean を注入して呼ぶ。"
                  + JAVA_CODING_DOC);

  /** プロキシで動くアノテーションを付けたメソッドを、プロキシが上書きできる private、final、static でないメソッドにする。 */
  @ArchTest
  /* package */ static final ArchRule proxiedMethodsAreNotPrivateFinalOrStatic =
      methods()
          .that(INTERCEPTED)
          .should(beOverridableByProxies())
          .allowEmptyShould(true)
          .because(
              "Spring の AOP プロキシはサブクラスでメソッドを上書きして呼び出しを横取りし、"
                  + "上書きできない private、final、static のメソッドではアノテーションが黙って効かないため。"
                  + "直し方：アノテーションを付けたメソッドから private、final、static を外し、別の Bean から注入して呼ぶ。"
                  + JAVA_CODING_DOC);

  /**
   * モジュールの public な Bean クラスの public メソッドを、プロキシが上書きできる final でないメソッドにする。
   *
   * <p>Spring Modulith の observability（{@code spring-modulith-starter-insight}）が、モジュールの public な
   * Bean を、アノテーションの有無によらず CGLIB のプロキシにする。
   */
  @ArchTest
  /* package */ static final ArchRule publicBeanMethodsAreNotFinal =
      methods()
          .that()
          .arePublic()
          .and()
          .areNotStatic()
          .and()
          .areDeclaredInClassesThat(
              DescribedPredicate.describe(
                  "public で @Component をメタアノテーションに持つ",
                  type ->
                      type.getModifiers().contains(JavaModifier.PUBLIC)
                          && type.isMetaAnnotatedWith(Component.class)))
          .should()
          .notHaveModifier(JavaModifier.FINAL)
          .allowEmptyShould(true)
          .because(
              "Spring Modulith の observability がモジュールの public な Bean をプロキシにし、"
                  + "final のメソッドはプロキシが上書きできず、注入したフィールドが null のプロキシの上で動いて NullPointerException になるため。"
                  + "起動とクラス単体のテストは通り、Spring を起動する統合テストだけが失敗する。"
                  + "直し方：public な Bean のメソッドから final を外す。"
                  + JAVA_CODING_DOC);

  /**
   * method security のアノテーションは、各モジュールの application のクラスとそのメソッドにだけ付ける。
   *
   * <p>Controller、domain、infrastructure に付けると、同じ操作の役割の確かめ方が層ごとに散る。
   */
  @ArchTest
  /* package */ static final ArchRule methodSecurityAnnotationsAreOnlyOnApplicationClasses =
      methodSecurityAnnotationsAreOnlyOnApplicationClassesRule(BASE_PACKAGE);

  /** {@code @PreAuthorize} には、役割か権限の名前だけを書く。 */
  @ArchTest
  /* package */ static final ArchRule preAuthorizeUsesOnlyRoleOrAuthorityNames =
      CompositeArchRule.of(
              classes()
                  .that()
                  .areAnnotatedWith(PreAuthorize.class)
                  .should(useOnlyRoleOrAuthorityNames())
                  .allowEmptyShould(true))
          .and(
              methods()
                  .that()
                  .areAnnotatedWith(PreAuthorize.class)
                  .should(useOnlyRoleOrAuthorityNames())
                  .allowEmptyShould(true))
          .because(
              "誰に許すかを役割で決め、見える範囲は Repository の SQL で絞ると決めており（ADR-129）、"
                  + "引数や Bean を参照する SpEL や、and と or で組んだ式を書くと、許す条件が式の中に隠れて二重になるため。"
                  + "直し方：@PreAuthorize の値を hasRole('ADMIN')、hasAnyRole('ADMIN', 'MEMBER')、"
                  + "hasAuthority('<権限>')、hasAnyAuthority('<権限>', ...) のどれか 1 つにし、"
                  + "本人や持ち主の確かめは、利用者の ID を Repository の WHERE に入れて行う。"
                  + LAYERS_DOC);

  /**
   * application の CommandHandler と QueryService の public
   * メソッドは、役割を確かめるか（{@code @PreAuthorize}）、確かめないか（{@link NoRoleRequired}）を宣言する。{@link
   * NoRoleRequired#reason()} は空白だけにしない。
   */
  @ArchTest
  /* package */ static final ArchRule applicationHandlersDeclareAuthorization =
      applicationHandlersDeclareAuthorizationRule(BASE_PACKAGE);

  /** {@link Async} のメソッドは、Spring が受け付ける void か {@link Future} を返す。 */
  @ArchTest
  /* package */ static final ArchRule asyncMethodsReturnVoidOrFuture =
      methods()
          .that()
          .areMetaAnnotatedWith(Async.class)
          .should()
          .haveRawReturnType(void.class)
          .orShould()
          .haveRawReturnType(JavaClass.Predicates.assignableTo(Future.class))
          .allowEmptyShould(true)
          .because(
              "Spring は戻り値が void と Future 以外の @Async のメソッドを呼ぶたびに IllegalArgumentException を投げ、"
                  + "void の @Async が投げた例外は呼び出し元に届かないため。"
                  + "直し方：結果か失敗を呼び出し元で扱うなら CompletableFuture を返し、扱わないなら void にする。"
                  + JAVA_CODING_DOC);

  /**
   * {@link Scheduled} のメソッドは、引数のない void のメソッドにし、private と final にしない。
   *
   * <p>static は禁じない。Spring はプロキシの Bean でも static の {@code @Scheduled} を呼べる（{@code
   * AopUtils.selectInvocableMethod}）。
   */
  @ArchTest
  /* package */ static final ArchRule scheduledMethodsAreNoArgVoidAndNotPrivateOrFinal =
      methods()
          .that()
          .areMetaAnnotatedWith(Scheduled.class)
          .or()
          .areMetaAnnotatedWith(Schedules.class)
          .should()
          .haveRawReturnType(void.class)
          .andShould()
          .haveRawParameterTypes(new Class<?>[0])
          .andShould()
          .notHaveModifier(JavaModifier.PRIVATE)
          .andShould()
          .notHaveModifier(JavaModifier.FINAL)
          .allowEmptyShould(true)
          .because(
              "スケジューラは @Scheduled のメソッドの戻り値を呼び出し元に返す値として使わず、"
                  + "Reactor があると、Mono などの Publisher を返すメソッドを一度だけ呼んで、返した Publisher を実行のたびに購読するため。"
                  + "引数のあるメソッドでは起動に失敗し、Bean が AOP のプロキシになると private のメソッドも起動に失敗し、"
                  + "final のメソッドはフィールドが空のプロキシの上で動くため。"
                  + "直し方：@Scheduled のメソッドを、引数のない void の、private と final でないメソッドにし、"
                  + "結果はログかメトリクスに出す。"
                  + JAVA_CODING_DOC);

  /** method security のアノテーションを各モジュールの application にだけ置く規則を組み立てる。 */
  /* package */ static ArchRule methodSecurityAnnotationsAreOnlyOnApplicationClassesRule(
      final String basePackage) {
    final String application = basePackage + ".*.application..";
    return CompositeArchRule.of(
            classes()
                .that(METHOD_SECURED)
                .should()
                .resideInAPackage(application)
                .allowEmptyShould(true))
        .and(
            methods()
                .that(METHOD_SECURED)
                .should()
                .beDeclaredInClassesThat()
                .resideInAPackage(application)
                .allowEmptyShould(true))
        .because(
            "誰が要求したか（役割を含む）は application で確かめると決めており（ADR-129）、"
                + "Controller、domain、infrastructure に付けると、操作の役割の確かめ方が層ごとに散るため。"
                + "直し方："
                + annotationNames(METHOD_SECURITY_ANNOTATIONS, "、")
                + " と、それをメタアノテーションに持つ注釈を、Controller、domain、infrastructure のクラスとメソッドから外し、"
                + "操作を受け持つ各モジュールの application の CommandHandler の handle に、"
                + "役割か権限の名前だけを書いた @PreAuthorize を付ける。"
                + LAYERS_DOC);
  }

  /**
   * application の CommandHandler と QueryService の public メソッドに、役割の判断の宣言を求める規則を組み立てる。
   *
   * <p>宣言は、メソッドかクラスに、直接かメタアノテーションとして付ける。static のメソッドはプロキシが横取りしないので除く。 {@link NoRoleRequired} の
   * {@code reason} の検査は、合成アノテーションに付けたものも見るため、パッケージを問わずクラスとメソッドに当てる。
   */
  /* package */ static ArchRule applicationHandlersDeclareAuthorizationRule(
      final String basePackage) {
    return CompositeArchRule.of(
            methods()
                .that()
                .arePublic()
                .and()
                .areNotStatic()
                .and()
                .areDeclaredInClassesThat(
                    JavaClass.Predicates.resideInAPackage(basePackage + ".*.application..")
                        .and(
                            JavaClass.Predicates.simpleNameEndingWith("CommandHandler")
                                .or(JavaClass.Predicates.simpleNameEndingWith("QueryService"))))
                .should(declareAuthorization())
                .allowEmptyShould(true))
        .and(
            classes()
                .that()
                .areAnnotatedWith(NoRoleRequired.class)
                .should(giveNoRoleRequiredReason())
                .allowEmptyShould(true))
        .and(
            methods()
                .that()
                .areAnnotatedWith(NoRoleRequired.class)
                .should(giveNoRoleRequiredReason())
                .allowEmptyShould(true))
        .because(
            "認可の表のテストは operation を足したときだけ期待の書き忘れを止め、"
                + "operation を通らずに呼ぶメソッド（Listener から呼ぶ CommandHandler）と、既存の operation の後ろに足したメソッドでは、"
                + "役割を判断しないまま @PreAuthorize のないメソッドが残るため（ADR-129）。"
                + "直し方：役割で許すメソッドには @PreAuthorize(\"hasRole('<役割>')\") を、"
                + "役割を問わないメソッドには @NoRoleRequired(reason = \"<理由>\") を、メソッドかクラスに付ける。"
                + "reason には、見える範囲の絞り方や呼び出し元（ログインの処理、Listener）など、役割を問わずに呼んでよい理由を書く。"
                + LAYERS_DOC);
  }

  @Test
  @DisplayName("プロキシの規則が見るアノテーションは、Java の実装規約の一覧と一致する")
  void interceptedAnnotationsMatchJavaCodingList() throws IOException {
    final List<String> listed =
        Files.readAllLines(REPOSITORY_ROOT.resolve(JAVA_CODING_PATH)).stream()
            .dropWhile(line -> !INTERCEPTED_HEADING.equals(line))
            .dropWhile(line -> !line.startsWith("- "))
            .takeWhile(line -> line.startsWith("- "))
            .flatMap(line -> ANNOTATION_NAME.matcher(line).results().map(result -> result.group(1)))
            .toList();

    assertThat(listed)
        .as(
            "%s の「%s」の最初の箇条書きが、INTERCEPTED_ANNOTATIONS と食い違う。"
                + "規則が見るアノテーションを規約の文書が示すようにするため。箇条書きと定数を同じ変更で直す",
            JAVA_CODING_PATH, INTERCEPTED_HEADING.substring("## ".length()))
        .containsExactlyInAnyOrderElementsOf(
            INTERCEPTED_ANNOTATIONS.stream().map(Class::getSimpleName).toList());
  }

  /** アノテーションのどれかを、直接かメタアノテーションとして持つ要素。 */
  private static DescribedPredicate<CanBeAnnotated> metaAnnotatedWithOneOf(
      final List<Class<? extends Annotation>> annotations) {
    return DescribedPredicate.describe(
        "are meta-annotated with one of " + annotationNames(annotations, ", "),
        annotated -> annotations.stream().anyMatch(annotated::isMetaAnnotatedWith));
  }

  /** アノテーションの名前を {@code @} 付きで区切って並べる。 */
  private static String annotationNames(
      final List<Class<? extends Annotation>> annotations, final String delimiter) {
    return annotations.stream()
        .map(type -> "@" + type.getSimpleName())
        .collect(Collectors.joining(delimiter));
  }

  private static <T extends HasAnnotations<?> & HasDescription>
      ArchCondition<T> useOnlyRoleOrAuthorityNames() {
    return new ArchCondition<>("write only role or authority names in @PreAuthorize") {
      @Override
      public void check(final T item, final ConditionEvents events) {
        item.tryGetAnnotationOfType(PreAuthorize.class)
            .map(PreAuthorize::value)
            .filter(expression -> !ROLE_OR_AUTHORITY_NAMES.matcher(expression).matches())
            .ifPresent(expression ->
                events.add(
                    SimpleConditionEvent.violated(
                        item,
                        item.getDescription()
                            + " の @PreAuthorize(\""
                            + expression
                            + "\") は、役割か権限の名前だけの式でない。")));
      }
    };
  }

  private static ArchCondition<JavaMethod> declareAuthorization() {
    return new ArchCondition<>("be annotated with @PreAuthorize or @NoRoleRequired") {
      @Override
      public void check(final JavaMethod item, final ConditionEvents events) {
        if (!DECLARES_AUTHORIZATION.test(item) && !DECLARES_AUTHORIZATION.test(item.getOwner())) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getFullName()
                      + " とそのクラスに、@PreAuthorize も @NoRoleRequired もない。 "
                      + item.getSourceCodeLocation()));
        }
      }
    };
  }

  private static <T extends HasAnnotations<?> & HasDescription>
      ArchCondition<T> giveNoRoleRequiredReason() {
    return new ArchCondition<>("give a non-blank reason in @NoRoleRequired") {
      @Override
      public void check(final T item, final ConditionEvents events) {
        item.tryGetAnnotationOfType(NoRoleRequired.class)
            .map(NoRoleRequired::reason)
            .filter(String::isBlank)
            .ifPresent(reason ->
                events.add(
                    SimpleConditionEvent.violated(
                        item,
                        item.getDescription()
                            + " の @NoRoleRequired(reason = \""
                            + reason
                            + "\") は、理由が空か空白だけである。")));
      }
    };
  }

  private static ArchCondition<JavaMethod> beOverridableByProxies() {
    return new ArchCondition<>("not be private, final or static") {
      @Override
      public void check(final JavaMethod item, final ConditionEvents events) {
        final List<String> modifiers =
            NOT_OVERRIDABLE.stream()
                .filter(item.getModifiers()::contains)
                .map(modifier -> modifier.name().toLowerCase(Locale.ROOT))
                .toList();
        if (!modifiers.isEmpty()) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getFullName()
                      + " は "
                      + String.join("、", modifiers)
                      + " で、プロキシが "
                      + String.join("、", interceptedAnnotationsOf(item))
                      + " を横取りできない。 "
                      + item.getSourceCodeLocation()));
        }
      }
    };
  }

  /** メソッドに付けたアノテーションのうち、プロキシで動くものと、それをメタアノテーションに持つものの名前を返す。 */
  private static List<String> interceptedAnnotationsOf(final JavaMethod method) {
    return method.getAnnotations().stream()
        .map(JavaAnnotation::getRawType)
        .filter(type ->
            INTERCEPTED_ANNOTATIONS.stream()
                .anyMatch(intercepted ->
                    type.isEquivalentTo(intercepted) || type.isMetaAnnotatedWith(intercepted)))
        .map(type -> "@" + type.getSimpleName())
        .toList();
  }

  private static ArchCondition<JavaClass> declareOrderOutsideTransaction() {
    return new ArchCondition<>("be annotated with @Order below Ordered.LOWEST_PRECEDENCE") {
      @Override
      public void check(final JavaClass item, final ConditionEvents events) {
        final Optional<Integer> order = item.tryGetAnnotationOfType(Order.class).map(Order::value);
        if (order.filter(value -> value < Ordered.LOWEST_PRECEDENCE).isEmpty()) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getFullName()
                      + order.map(value -> " の @Order が " + value + " で").orElse(" に @Order がなく")
                      + "、トランザクションの advisor より外側で動かない。"));
        }
      }
    };
  }

  private static ArchCondition<JavaMethod> declarePointcutWithWithin() {
    return new ArchCondition<>("declare a pointcut expression containing within(") {
      @Override
      public void check(final JavaMethod item, final ConditionEvents events) {
        final List<String> expressions = pointcutExpressionsOf(item);
        if (expressions.stream().noneMatch(expression -> expression.contains("within("))) {
          events.add(
              SimpleConditionEvent.violated(
                  item, item.getFullName() + " の pointcut の式 " + expressions + " に within がない。"));
        }
      }
    };
  }

  /** AspectJ のアノテーションの {@code value} と {@code pointcut} に書いた式を返す。空の式は除く。 */
  private static List<String> pointcutExpressionsOf(final JavaMethod method) {
    final Set<String> annotationNames =
        POINTCUT_ANNOTATIONS.stream().map(Class::getName).collect(Collectors.toSet());
    return method.getAnnotations().stream()
        .filter(annotation -> annotationNames.contains(annotation.getRawType().getName()))
        .flatMap(annotation -> Stream.of("value", "pointcut").map(annotation::get))
        .flatMap(Optional::stream)
        .map(String::valueOf)
        .filter(expression -> !expression.isBlank())
        .toList();
  }
}
