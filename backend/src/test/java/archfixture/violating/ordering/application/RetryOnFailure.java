package archfixture.violating.ordering.application;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.resilience.annotation.Retryable;

/** Spring の {@code @Retryable} をメタアノテーションに持つ合成アノテーション。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Retryable
public @interface RetryOnFailure {}
