package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.ProxyRules;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.prepost.PreFilter;
import org.springframework.transaction.annotation.Transactional;

/** Spring と Resilience4j のプロキシを迂回する同一クラス内の直接呼び出しを禁止する。 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class ProxyRulesArchTest {

  /** pointcut の式を持つ AspectJ のアノテーション。 */
  private static final Set<Class<? extends Annotation>> POINTCUT_ANNOTATIONS =
      Set.of(
          Around.class,
          Before.class,
          After.class,
          AfterReturning.class,
          AfterThrowing.class,
          Pointcut.class);

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
                  + "規約：docs/backend/java-coding.md、"
                  + "docs/adr/ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md");

  /** {@link Transactional} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule transactionalMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(Transactional.class);

  /** {@link Async} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule asyncMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(Async.class);

  /** {@link Cacheable} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule cacheableMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(Cacheable.class);

  /** {@link CachePut} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule cachePutMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(CachePut.class);

  /** {@link CacheEvict} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule cacheEvictMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(CacheEvict.class);

  /** {@link PreAuthorize} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule preAuthorizeMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(PreAuthorize.class);

  /** {@link PostAuthorize} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule postAuthorizeMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(PostAuthorize.class);

  /** {@link PreFilter} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule preFilterMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(PreFilter.class);

  /** {@link PostFilter} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule postFilterMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(PostFilter.class);

  /** {@link Secured} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule securedMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(Secured.class);

  /** Resilience4j の {@link CircuitBreaker} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule circuitBreakerMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(CircuitBreaker.class);

  /** Resilience4j の {@link Retry} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule retryMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(Retry.class);

  /** Resilience4j の {@link RateLimiter} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule rateLimiterMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(RateLimiter.class);

  /** Resilience4j の {@link Bulkhead} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule bulkheadMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(Bulkhead.class);

  /** Resilience4j の {@link TimeLimiter} メソッドの自己呼び出しを禁止する。 */
  @ArchTest
  /* package */ static final ArchRule timeLimiterMethodsAreNotCalledFromSameClass =
      prohibitSelfInvocationOf(TimeLimiter.class);

  private static ArchRule prohibitSelfInvocationOf(
      final Class<? extends Annotation> annotationType) {
    return ProxyRules
        .no_classes_should_directly_call_other_methods_declared_in_the_same_class_that_are_annotated_with(
            annotationType)
        .because(
            "Spring の AOP プロキシは Bean の外からの呼び出しだけを横取りし、同じクラスの中からの呼び出しでは @"
                + annotationType.getSimpleName()
                + " が黙って効かないため。"
                + "直し方：@"
                + annotationType.getSimpleName()
                + " のメソッドを同じクラスの中から呼ばず、別の Bean へ移して、その Bean を注入して呼ぶ。"
                + "規約：docs/backend/java-coding.md");
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
