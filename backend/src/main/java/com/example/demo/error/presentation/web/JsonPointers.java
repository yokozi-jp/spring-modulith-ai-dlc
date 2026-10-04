package com.example.demo.error.presentation.web;

import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.RequestBody;

/** 入力検証の誤りの位置を RFC 6901 の JSON Pointer にする。 */
final class JsonPointers {

  /** Spring のプロパティパスの区切り（{@code .} と {@code [}）。 */
  private static final Pattern PROPERTY_PATH_SEPARATOR = Pattern.compile("[.\\[]");

  private JsonPointers() {}

  /** Spring のプロパティパス（{@code items[0].code}）を、起点の下の JSON Pointer にする。 */
  /* package */ static String fromPropertyPath(final String origin, final String propertyPath) {
    // ponytail: map の key が . [ ] を含むと segment を誤って分ける。
    // そうした key を使うようになったら Bean Validation の Path から作る。
    return origin
        + PROPERTY_PATH_SEPARATOR
            .splitAsStream(propertyPath.replace("]", ""))
            .filter(segment -> !segment.isEmpty())
            .map(segment -> "/" + escape(segment))
            .collect(Collectors.joining());
  }

  /** メソッドの引数の起点。{@code @RequestBody} は本文の root、それ以外は {@code /<パラメータ名>}。 */
  /* package */ static String origin(final ParameterValidationResult result) {
    final MethodParameter parameter = result.getMethodParameter();
    // ponytail: @RequestParam("x") の別名は使わず Java のパラメータ名にするため、別名を付けるとずれる。
    // 別名を使うようになったら annotation の name を読む。
    final @Nullable String name = parameter.getParameterName();
    final String origin =
        parameter.hasParameterAnnotation(RequestBody.class) || name == null
            ? ""
            : "/" + escape(name);
    return origin + containerSegment(result.getContainerIndex(), result.getContainerKey());
  }

  /** Bean Validation の Path を JSON Pointer にする。メソッドと要素の node は名前を使わない。 */
  /* package */ static String fromPath(final Path path) {
    final StringBuilder pointer = new StringBuilder();
    for (final Path.Node node : path) {
      if (node.isInIterable()) {
        pointer.append(containerSegment(node.getIndex(), node.getKey()));
      }
      final ElementKind kind = node.getKind();
      final @Nullable String name = node.getName();
      if ((kind == ElementKind.PARAMETER || kind == ElementKind.PROPERTY) && name != null) {
        pointer.append('/').append(escape(name));
      }
    }
    return pointer.toString();
  }

  /** 反復の中の位置の segment。index も key もなければ（Set の要素）空にする。 */
  private static String containerSegment(
      final @Nullable Integer index, final @Nullable Object key) {
    if (index != null) {
      return "/" + index;
    }
    return key == null ? "" : "/" + escape(key.toString());
  }

  /** RFC 6901 §3 の escape。~ を先に置き換える。 */
  private static String escape(final String segment) {
    return segment.replace("~", "~0").replace("/", "~1");
  }
}
