package com.example.demo.testkit.idempotency;

import com.example.demo.shared.events.ListenerIds;
import java.lang.reflect.Method;
import java.util.Set;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * イベント出版のリスナーの ID（{@code listener_id}）。規則は本番の {@link ListenerIds} と同じものを使う。
 *
 * @param value {@code <クラス>.<メソッド>(<型>,...)}、または注釈の {@code id}
 */
public record ListenerId(String value) {

  /** リスナーのクラスが持つ {@code @ApplicationModuleListener} のメソッドの数。 */
  private static final int LISTENER_METHODS = 1;

  /**
   * {@code @ApplicationModuleListener} を付けたメソッドの ID を返す。
   *
   * @throws IllegalArgumentException メソッドに {@code @ApplicationModuleListener} がない場合
   */
  /* package */ static ListenerId forMethod(final Method method) {
    return new ListenerId(ListenerIds.fromMethod(method));
  }

  /**
   * リスナーのクラスの、ただ 1 つの {@code @ApplicationModuleListener} のメソッドの ID を返す。
   *
   * @throws IllegalArgumentException そのメソッドが 1 つでない場合
   */
  public static ListenerId forListener(final Class<?> listener) {
    final Set<Method> methods = listenerMethods(listener);
    if (methods.size() != LISTENER_METHODS) {
      throw new IllegalArgumentException(
          listener.getName()
              + " must declare exactly one @ApplicationModuleListener method: "
              + methods);
    }
    return forMethod(methods.iterator().next());
  }

  /** クラスの {@code @ApplicationModuleListener} のメソッド。 */
  /* package */ static Set<Method> listenerMethods(final Class<?> type) {
    return MethodIntrospector.selectMethods(
        type,
        (Method method) ->
            AnnotatedElementUtils.hasAnnotation(method, ApplicationModuleListener.class));
  }

  @Override
  public String toString() {
    return value;
  }
}
