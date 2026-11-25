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
 * のインタフェース（サブパッケージを含む）の JDK proxy で包む。順序のない後処理なので、AOP の proxy を作った後の Bean を包む。テストの構成だけに入れる。
 */
/* package */ final class ExternalCallRecorder implements BeanPostProcessor {

  /** 記録した呼び出し。リスナーのスレッドが足し、テストのスレッドが読む。 */
  private final Queue<ExternalCall> calls = new ConcurrentLinkedQueue<>();

  /**
   * {@code ..infrastructure.client..} の {@code *Client} を、{@code ..domain.model..} のインタフェースの proxy
   * で包む。
   *
   * @throws IllegalStateException {@code *Client} が {@code ..domain.model..} のインタフェースを 1
   *     つも実装しない場合。包まずに通すと、外部システムの呼び出しの検査が黙って空振りするため、文脈の起動で失敗させる
   */
  @Override
  public Object postProcessAfterInitialization(final Object bean, final String beanName) {
    final Class<?> type = ClassUtils.getUserClass(bean);
    if (!inPackage(type, "infrastructure.client") || !type.getSimpleName().endsWith("Client")) {
      return bean;
    }
    final Class<?>[] ports =
        Arrays.stream(ClassUtils.getAllInterfacesForClass(type))
            .filter(ExternalCallRecorder::isPort)
            .toArray(Class<?>[]::new);
    if (ports.length == 0) {
      throw new IllegalStateException(
          beanName
              + " ("
              + type.getName()
              + ") implements no interface in ..domain.model..,"
              + " so the idempotency check cannot record its external calls");
    }
    final ProxyFactory proxy = new ProxyFactory(ports);
    proxy.setTarget(bean);
    proxy.addAdvice(
        (MethodInterceptor)
            invocation -> {
              // proxy は Object のメソッドも通すため、外部システムのインタフェースのメソッドだけを記録する。
              final Class<?> port = invocation.getMethod().getDeclaringClass();
              if (isPort(port)) {
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

  /** 外部システムのインタフェース（{@code ..domain.model..} のサブパッケージを含む）か。 */
  private static boolean isPort(final Class<?> type) {
    return inPackage(type, "domain.model");
  }

  /** 型のパッケージが、{@code segments} のパッケージの区切りをちょうど含むか。{@code ..domain.modelx..} は含まない。 */
  private static boolean inPackage(final Class<?> type, final String segments) {
    return ("." + type.getPackageName() + ".").contains("." + segments + ".");
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
