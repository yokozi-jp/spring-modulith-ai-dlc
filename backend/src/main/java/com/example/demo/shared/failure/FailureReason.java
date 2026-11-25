package com.example.demo.shared.failure;

import java.io.Serializable;

/**
 * 422 になる業務規則の違反の理由。モジュールごとの enum が実装し、{@link BusinessRuleViolationException} に渡す。
 *
 * <p>理由は、enum を置いたモジュールの名前と定数の名前で識別する。problem type と title のキーは {@code error} モジュールがこの 2
 * つから作る（ADR-125）。
 *
 * <p>例外とともに直列化されるため {@link Serializable} を継承する。enum は直列化できる。
 */
// enum が実装する interface で、ラムダで作らない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface FailureReason extends Serializable {

  /**
   * アプリケーションのベースパッケージ。
   *
   * <p>{@code shared} はルートパッケージの {@code DemoApplication} に依存できないため、文字列で持つ。 {@code
   * FailureReasonTest} が {@code DemoApplication} のパッケージと一致することを確かめる。
   */
  String BASE_PACKAGE = "com.example.demo";

  /** 理由の定数の名前を返す。enum の {@link Enum#name()} が実装する。 */
  String name();

  /**
   * 理由を置いたモジュールの名前を返す。{@code com.example.demo.<module>} の下のパッケージの {@code <module>} である。
   *
   * <p>この名前は Spring Modulith がパッケージから決めるモジュールの名前と同じになる。 実装をベースパッケージの下の enum に限ることは、ArchUnit の
   * {@code failureReasonsAreModuleEnums} が確かめる。
   */
  default String module() {
    // 末尾に . を足し、モジュールのルートのパッケージ（下のパッケージがない）でも最初の . で切れるようにする。
    final String relative = getClass().getPackageName().substring(BASE_PACKAGE.length() + 1) + ".";
    return relative.substring(0, relative.indexOf('.'));
  }
}
