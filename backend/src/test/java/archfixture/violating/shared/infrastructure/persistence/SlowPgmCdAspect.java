package archfixture.violating.shared.infrastructure.persistence;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jspecify.annotations.Nullable;

/** 違反：pointcutsNarrowTargetsWithWithin（{@code execution} の宣言型だけで対象を絞り、{@code within} を書かない）。 */
@Aspect
public class SlowPgmCdAspect {

  /** CommandHandler の handle を囲む。 */
  @Around("execution(* archfixture.violating.*..*CommandHandler.handle(..))")
  public @Nullable Object bind(final ProceedingJoinPoint joinPoint) throws Throwable {
    return joinPoint.proceed();
  }

  /** Listener の public メソッドの後に何もしない。式を {@code pointcut} 属性に書く。 */
  @AfterReturning(pointcut = "execution(public * archfixture.violating.*..*Listener.*(..))")
  public void afterListener() {
    // 違反の形を作るためだけのメソッド。
  }
}
