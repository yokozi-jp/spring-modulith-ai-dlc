package archfixture.conforming.shared.infrastructure.persistence;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jspecify.annotations.Nullable;

/** pointcut の式に {@code within} を書き、対象のクラスを型の名前で絞る Aspect。 */
@Aspect
public class PgmCdAspect {

  /** CommandHandler の handle を囲む。 */
  @Around("within(archfixture.conforming.*..*CommandHandler) && execution(* handle(..))")
  public @Nullable Object bind(final ProceedingJoinPoint joinPoint) throws Throwable {
    return joinPoint.proceed();
  }
}
