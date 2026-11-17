package archfixture.violating.shared.infrastructure.persistence;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jspecify.annotations.Nullable;

/** 違反：aspectsDeclareOrder（{@code @Aspect} のクラスに {@code @Order} を付けない）。 */
@Aspect
public class UnorderedPgmCdAspect {

  /** CommandHandler の handle を囲む。 */
  @Around("within(archfixture.violating.*..*CommandHandler) && execution(* handle(..))")
  public @Nullable Object bind(final ProceedingJoinPoint joinPoint) throws Throwable {
    return joinPoint.proceed();
  }
}
