package com.example.demo.persistence.conventions;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 規約の例外として認める違反を、理由とともに表す許可リストの一件。
 *
 * <p>{@code rule}と{@code target}の両方が違反と完全に一致したときだけ、その違反を抑止する。何も抑止しなかった項目は、許可リストが古くならないよう{@code
 * ALLOWLIST}の違反として報告する。
 *
 * @param rule 抑止する規則の記号
 * @param target 抑止する対象
 * @param reason 例外にする理由
 */
record AllowlistEntry(String rule, String target, String reason) {

  /** 使われなかった許可リストの項目を報告する規則の記号。 */
  /* package */ static final String UNUSED_RULE = "ALLOWLIST";

  /**
   * 違反を同じキーで1件にまとめ、許可リストに一致した違反を除き、使われなかった項目を違反として加える。
   *
   * @param found 検査が見つけた違反
   * @param allowlist 許可リスト
   * @return 抑止されなかった違反と、使われなかった許可リストの項目
   */
  /* package */ static List<ConventionViolation> apply(
      final Collection<ConventionViolation> found, final List<AllowlistEntry> allowlist) {
    final Set<String> allowed =
        allowlist.stream().map(AllowlistEntry::key).collect(Collectors.toUnmodifiableSet());
    final Collection<ConventionViolation> distinct =
        found.stream()
            .collect(
                Collectors.toMap(
                    ConventionViolation::key,
                    Function.identity(),
                    (first, second) -> first,
                    LinkedHashMap::new))
            .values();
    final Set<String> foundKeys =
        distinct.stream().map(ConventionViolation::key).collect(Collectors.toUnmodifiableSet());
    return Stream.concat(
            distinct.stream().filter(violation -> !allowed.contains(violation.key())),
            allowlist.stream()
                .filter(entry -> !foundKeys.contains(entry.key()))
                .map(
                    entry ->
                        new ConventionViolation(
                            UNUSED_RULE,
                            entry.key(),
                            "許可リストの項目が違反を一つも抑止していない。不要になった項目を許可リストから削除する。")))
        .toList();
  }

  /** 違反の{@link ConventionViolation#key()}と照合するキーを返す。 */
  /* package */ String key() {
    return rule + " " + target;
  }
}
