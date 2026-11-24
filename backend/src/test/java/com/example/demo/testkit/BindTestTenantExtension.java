package com.example.demo.testkit;

import com.example.demo.shared.tenancy.TenantContext;
import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * テストのメソッドと {@code @BeforeEach} を、{@link AsTenant#TENANT} のテナントを束縛して呼ぶ JUnit の拡張。
 *
 * <p>本番の CommandHandler と QueryService は、リクエストの入口の filter かリスナーの Aspect
 * が束縛したテナントの中で呼ばれる。それらをテストのスレッドから直接呼ぶテストのクラスに {@code @ExtendWith}
 * で付け、トランザクションマネージャーがテナントを設定できるようにする。
 */
public final class BindTestTenantExtension implements InvocationInterceptor {

  @Override
  public void interceptBeforeEachMethod(
      final Invocation<Void> invocation,
      final ReflectiveInvocationContext<Method> invocationContext,
      final ExtensionContext extensionContext)
      throws Throwable {
    inTestTenant(invocation);
  }

  @Override
  public void interceptTestMethod(
      final Invocation<Void> invocation,
      final ReflectiveInvocationContext<Method> invocationContext,
      final ExtensionContext extensionContext)
      throws Throwable {
    inTestTenant(invocation);
  }

  @Override
  public void interceptTestTemplateMethod(
      final Invocation<Void> invocation,
      final ReflectiveInvocationContext<Method> invocationContext,
      final ExtensionContext extensionContext)
      throws Throwable {
    inTestTenant(invocation);
  }

  private static void inTestTenant(final Invocation<Void> invocation) throws Throwable {
    TenantContext.<Void, Throwable>call(AsTenant.TENANT, invocation::proceed);
  }
}
