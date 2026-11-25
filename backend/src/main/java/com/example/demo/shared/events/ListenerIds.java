package com.example.demo.shared.events;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

/**
 * イベント出版のリスナーの ID（{@code listener_id}）を、リスナーのメソッドから作る。
 *
 * <p>Spring Modulith 2.2.0 は {@code TransactionalApplicationListener.getListenerId()} を ID
 * にし、Spring Framework の {@code ApplicationListenerMethodAdapter} は、注釈の {@code id}
 * が空ならメソッドの完全修飾名と引数の型（{@code <クラス>.<メソッド>(<型>,...)}）を ID
 * にする。再投入の方針の対応づけと、リスナーの冪等性の全件のテストが同じ規則を使うため、規則をここだけに置く。
 */
public final class ListenerIds {

  private ListenerIds() {}

  /**
   * リスナーのメソッドの ID を返す。
   *
   * @param method {@code @ApplicationModuleListener} を付けたメソッド。proxy でなく元のクラスのメソッドを渡す
   * @return リスナーの ID。メソッドに {@code @EventListener}（{@code @ApplicationModuleListener} が含む）がなければ空
   */
  public static Optional<String> fromMethod(final Method method) {
    final @Nullable EventListener listener =
        AnnotatedElementUtils.findMergedAnnotation(method, EventListener.class);
    if (listener == null) {
      return Optional.empty();
    }
    if (!listener.id().isEmpty()) {
      return Optional.of(listener.id());
    }
    final StringJoiner parameters = new StringJoiner(",", "(", ")");
    for (final Class<?> type : method.getParameterTypes()) {
      parameters.add(type.getName());
    }
    return Optional.of(ClassUtils.getQualifiedMethodName(method) + parameters);
  }
}
