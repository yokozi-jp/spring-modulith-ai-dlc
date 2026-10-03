package com.example.demo.persistence.conventions;

/**
 * DB規約の検査が見つけた違反の一件。
 *
 * <p>{@code rule}は規則の記号（{@code M14}、{@code C1}など）、{@code target}は違反した対象（changesetのid、ファイル名、{@code
 * schema.table.column}など）、{@code detail}は違反の内容と規約の文書を表す。許可リストは{@code rule}と{@code target}の組で照合する。
 *
 * @param rule 規則の記号
 * @param target 違反した対象
 * @param detail 違反の内容と、規則を定める文書
 */
record ConventionViolation(String rule, String target, String detail) {

  /** 許可リストとの照合と、重複の排除に使うキーを返す。 */
  /* package */ String key() {
    return rule + " " + target;
  }

  @Override
  public String toString() {
    return "[" + rule + "] " + target + ": " + detail;
  }
}
