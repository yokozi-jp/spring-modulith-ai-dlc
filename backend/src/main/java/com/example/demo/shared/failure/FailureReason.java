package com.example.demo.shared.failure;

import java.util.Locale;

/**
 * 422 になる業務規則の違反の理由。モジュールごとの enum が実装し、{@link BusinessRuleViolationException} に渡す。
 *
 * <p>problem type と title のキーは、enum のパッケージから取るモジュールの名前と、定数の名前の kebab-case から作る（ADR-125）。 たとえば
 * {@code com.example.demo.ordering.domain.model} の {@code NOT_DRAFT} は、type が {@code
 * /problems/ordering/not-draft}、title のキーが {@code problem.title.ordering.not-draft} になる。
 *
 * <p>sealed にしない。名前のないモジュールでは、sealed の型の permits が同じパッケージに限られるためである。
 */
public interface FailureReason {

  /** 理由の定数の名前を返す。enum の {@link Enum#name()} が実装する。 */
  String name();

  /** {@code /problems/<module>/<kebab-case>} の problem type を返す。 */
  default String type() {
    return "/problems/" + module() + "/" + kebabName();
  }

  /** {@code problem.title.<module>.<kebab-case>} の title のキーを返す。 */
  default String titleKey() {
    return "problem.title." + module() + "." + kebabName();
  }

  /** {@code com.example.demo.<module>} の下のパッケージから、モジュールの名前を取る。 */
  private String module() {
    final String basePackage = "com.example.demo.";
    final String packageName = getClass().getPackageName();
    if (!packageName.startsWith(basePackage)) {
      throw new IllegalStateException(
          "failure reason must reside in a module under " + basePackage + ": " + getClass());
    }
    final String relative = packageName.substring(basePackage.length());
    final int dot = relative.indexOf('.');
    return dot < 0 ? relative : relative.substring(0, dot);
  }

  private String kebabName() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
