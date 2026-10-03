package com.example.demo.persistence.conventions;

import java.util.List;
import java.util.Map;

/** SnakeYAMLが返す{@code Map}と{@code List}の値を、型を確かめて取り出す。 */
final class YamlNodes {

  private YamlNodes() {
    // 静的メソッドだけを持つ。
  }

  /**
   * {@code Map}なら、文字列のキーを持つ{@code Map}として返す。それ以外は空の{@code Map}を返す。
   *
   * @param node YAMLの値
   * @return 文字列のキーを持つ{@code Map}
   */
  @SuppressWarnings("unchecked")
  /* package */ static Map<String, Object> map(final Object node) {
    return node instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  /**
   * {@code List}ならそのまま返す。それ以外は空の{@code List}を返す。
   *
   * @param node YAMLの値
   * @return 要素の一覧
   */
  /* package */ static List<?> list(final Object node) {
    return node instanceof List<?> list ? list : List.of();
  }

  /**
   * 値を文字列で返す。値がなければ空文字を返す。
   *
   * @param node YAMLの{@code Map}
   * @param key キー
   * @return 値の文字列表現
   */
  /* package */ static String text(final Map<String, Object> node, final String key) {
    final Object value = node.get(key);
    return value == null ? "" : value.toString();
  }

  /**
   * YAMLの真偽値か文字列の{@code true}か。
   *
   * @param value YAMLの値
   * @return {@code true}を表すか
   */
  /* package */ static boolean isTrue(final Object value) {
    return Boolean.TRUE.equals(value) || "true".equals(value);
  }

  /**
   * YAMLの真偽値か文字列の{@code false}か。
   *
   * @param value YAMLの値
   * @return {@code false}を表すか
   */
  /* package */ static boolean isFalse(final Object value) {
    return Boolean.FALSE.equals(value) || "false".equals(value);
  }
}
