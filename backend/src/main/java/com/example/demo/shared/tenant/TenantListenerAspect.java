package com.example.demo.shared.tenant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@code @ApplicationModuleListener} の呼び出しの間だけ、イベントのテナントを {@link TenantContext} に束縛する。
 *
 * <p>トランザクションの advisor（Spring の既定の {@link Ordered#LOWEST_PRECEDENCE}）より外側で動き、リスナーのトランザクションが始まる時点で
 * テナントを束縛済みにする。{@code PgmCdAspect} より外側に置く。
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 200)
@Component
public class TenantListenerAspect {

  /**
   * イベントのテナントを、リスナーの呼び出しの間だけ束縛する。
   *
   * <p>対象のクラスは {@code within} の名前で絞る。継承した {@code on} は対象にしないため、Listener の継承を ArchUnit の {@code
   * commandHandlersAndListenersDoNotUseInheritance} で禁じる。
   *
   * @throws IllegalStateException 引数のイベントが {@link TenantScoped} でない場合。ArchUnit の {@code
   *     moduleListenerEventsAreTenantScoped} が先に止める
   */
  @Around(
      "within(com.example.demo.*..*Listener)"
          + " && @annotation(org.springframework.modulith.events.ApplicationModuleListener)")
  public @Nullable Object bindTenant(final ProceedingJoinPoint joinPoint) throws Throwable {
    final Object[] arguments = joinPoint.getArgs();
    if (arguments.length != 1 || !(arguments[0] instanceof TenantScoped event)) {
      throw new IllegalStateException(
          "the event of an @ApplicationModuleListener must implement TenantScoped: "
              + joinPoint.getSignature());
    }
    return TenantContext.call(event.tenantId(), joinPoint::proceed);
  }
}
