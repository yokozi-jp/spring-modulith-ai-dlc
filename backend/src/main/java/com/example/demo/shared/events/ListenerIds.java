package com.example.demo.shared.events;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.StringJoiner;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

/**
 * イベント出版のリスナーの ID（{@code listener_id}）を、リスナーのメソッドから作る。
 *
 * <p>Spring Modulith 2.2.0 は {@code TransactionalApplicationListener.getListenerId()} を ID
 * にし、Spring Framework の {@code ApplicationListenerMethodAdapter} は、注釈の {@code id}
 * が空ならメソッドの完全修飾名と引数の型（{@code <クラス>.<メソッド>(<型>,...)}）を ID
 * にする。再投入の方針の対応づけと、リスナーの冪等性の全件のテストが同じ規則を使うため、規則と、リスナーを探す bean の走査をここだけに置く。
 */
@Slf4j
public final class ListenerIds {

  private ListenerIds() {}

  /**
   * リスナーのメソッドの ID を返す。
   *
   * @param method {@code @ApplicationModuleListener} を付けたメソッド。proxy でなく元のクラスのメソッドを渡す
   * @return リスナーの ID
   * @throws IllegalArgumentException メソッドに
   *     {@code @EventListener}（{@code @ApplicationModuleListener} が含む）がない場合
   */
  public static String fromMethod(final Method method) {
    final @Nullable EventListener listener =
        AnnotatedElementUtils.findMergedAnnotation(method, EventListener.class);
    if (listener == null) {
      throw new IllegalArgumentException("not an @ApplicationModuleListener method: " + method);
    }
    if (!listener.id().isEmpty()) {
      return listener.id();
    }
    final StringJoiner parameters = new StringJoiner(",", "(", ")");
    for (final Class<?> type : method.getParameterTypes()) {
      parameters.add(type.getName());
    }
    return ClassUtils.getQualifiedMethodName(method) + parameters;
  }

  /**
   * bean の proxy の元のクラスを、bean の定義の順に返す。型を解決できない bean は読み飛ばす。
   *
   * <p>{@code EventListenerMethodProcessor} も同じ呼び出しを囲んで読み飛ばす。型を解決できない bean は
   * {@code @ApplicationModuleListener} を持てないため、無関係な bean 1 つで走査を落とさない。{@code OutOfMemoryError} などの
   * {@code Error} は受けない。
   *
   * @param beanFactory 走査する bean の定義
   * @return リスナーのメソッドを探すクラス。proxy でなく元のクラス
   */
  public static Stream<Class<?>> beanClasses(final ConfigurableListableBeanFactory beanFactory) {
    return Arrays.stream(beanFactory.getBeanDefinitionNames())
        .flatMap(beanName -> Stream.ofNullable(targetClassOf(beanFactory, beanName)));
  }

  // 遅延の bean や欠けたクラスで型の解決が RuntimeException か LinkageError になり、種類を絞れないため受ける。
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static @Nullable Class<?> targetClassOf(
      final ConfigurableListableBeanFactory beanFactory, final String beanName) {
    try {
      return AutoProxyUtils.determineTargetClass(beanFactory, beanName);
    } catch (RuntimeException | LinkageError e) {
      log.atDebug()
          .addKeyValue("bean", beanName)
          .setCause(e)
          .log("Skipped a bean whose type cannot be resolved");
      return null;
    }
  }
}
