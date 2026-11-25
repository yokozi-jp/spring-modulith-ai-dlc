package com.example.demo.testkit.idempotency;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ClassUtils;

/**
 * 外部システムのインタフェースの呼び出しを、(インタフェース, メソッド, 引数) の組で記録する。
 *
 * <p>{@code ..infrastructure.client..} の {@code *Client} の Bean を、それが実装する {@code ..domain.model..}
 * のインタフェースの JDK proxy で包む。順序のない後処理なので、AOP の proxy を作った後の Bean を包む。テストの構成だけに入れる。
 */
/* package */ final class ExternalCallRecorder implements BeanPostProcessor {

  /** 外部システムのインタフェースを置くパッケージの接尾辞。 */
  private static final String PORT_PACKAGE = ".domain.model";

  /** 記録した呼び出し。リスナーのスレッドが足し、テストのスレッドが読む。 */
  private final Queue<ExternalCall> calls = new ConcurrentLinkedQueue<>();

  @Override
  public Object postProcessAfterInitialization(final Object bean, final String beanName) {
    final Class<?> type = ClassUtils.getUserClass(bean);
    if (!type.getPackageName().contains(".infrastructure.client")
        || !type.getSimpleName().endsWith("Client")) {
      return bean;
    }
    final Class<?>[] ports =
        Arrays.stream(ClassUtils.getAllInterfacesForClass(type))
            .filter(port -> port.getPackageName().endsWith(PORT_PACKAGE))
            .toArray(Class<?>[]::new);
    if (ports.length == 0) {
      return bean;
    }
    final ProxyFactory proxy = new ProxyFactory(ports);
    proxy.setTarget(bean);
    proxy.addAdvice(
        (MethodInterceptor)
            invocation -> {
              final Class<?> port = invocation.getMethod().getDeclaringClass();
              if (port.getPackageName().endsWith(PORT_PACKAGE)) {
                calls.add(
                    new ExternalCall(
                        port.getName(),
                        invocation.getMethod().getName(),
                        Collections.unmodifiableList(
                            Arrays.asList(invocation.getArguments().clone()))));
              }
              return invocation.proceed();
            });
    return proxy.getProxy();
  }

  /** 記録を消す。 */
  /* package */ void reset() {
    calls.clear();
  }

  /** 記録した呼び出しの組の集合。同じ組の繰り返しは 1 つにまとめる。 */
  /* package */ Set<ExternalCall> distinct() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(calls));
  }

  /**
   * 外部システムのインタフェースの 1 回の呼び出し。
   *
   * @param port インタフェースの完全修飾名
   * @param method メソッドの名前
   * @param arguments 引数
   */
  /* package */ record ExternalCall(String port, String method, List<Object> arguments) {}
}
