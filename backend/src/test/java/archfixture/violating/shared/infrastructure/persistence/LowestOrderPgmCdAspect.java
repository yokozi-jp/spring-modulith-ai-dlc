package archfixture.violating.shared.infrastructure.persistence;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;

/** 違反：aspectsDeclareOrder（値のない {@code @Order} で、トランザクションの advisor と同じ順序になる）。 */
@Aspect
@Order
public class LowestOrderPgmCdAspect {

  /** CommandHandler の handle を囲む。 */
  @Around("within(archfixture.violating.*..*CommandHandler) && execution(* handle(..))")
  public @Nullable Object bind(final ProceedingJoinPoint joinPoint) throws Throwable {
    return joinPoint.proceed();
  }
}
