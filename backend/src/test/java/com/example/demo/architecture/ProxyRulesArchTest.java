package com.example.demo.architecture;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.ProxyRules;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.lang.annotation.Annotation;
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
            annotationType);
  }
}
