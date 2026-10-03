package com.example.demo.persistence.conventions;

import static java.util.Map.entry;

import com.example.demo.persistence.conventions.ChangesetReplay.ColumnShape;
import com.example.demo.persistence.conventions.ChangesetReplay.TableShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 業務テーブルの最終形について、共通カラムの規則（C1からC3）を判定する。 */
final class ChangesetCommonColumnRules {

  /** 共通カラムの規約の文書。 */
  private static final String COMMON_COLUMNS_DOC = "docs/database/postgresql-common-columns.md";

  /** 日時の共通カラムの型。 */
  private static final String TIMESTAMPTZ = "timestamptz";

  /** 作業者とプログラムの共通カラムの型。 */
  private static final String VARCHAR_64 = "varchar(64)";

  /** trace IDの共通カラムの型。 */
  private static final String VARCHAR_32 = "varchar(32)";

  /** 共通カラムの名前から、型とNULLを許すかへの対応（postgresql-common-columns.mdの表の順）。 */
  private static final List<Map.Entry<String, ColumnShape>> COMMON_COLUMNS =
      List.of(
          entry("created_at", required(TIMESTAMPTZ)),
          entry("created_by", required(VARCHAR_64)),
          entry("created_pgm_cd", required(VARCHAR_64)),
          entry("created_tx_id", required(VARCHAR_32)),
          entry("updated_at", required(TIMESTAMPTZ)),
          entry("updated_by", required(VARCHAR_64)),
          entry("updated_pgm_cd", required(VARCHAR_64)),
          entry("updated_tx_id", required(VARCHAR_32)),
          entry("lock_no", required("bigint")),
          entry("patched_at", new ColumnShape(TIMESTAMPTZ, true, false)),
          entry("patched_by", new ColumnShape(VARCHAR_64, true, false)),
          entry("patched_id", new ColumnShape("integer", true, false)));

  /**
   * 追記だけのワークテーブル（{@code w_}）が、changesetの{@code comment}に理由を書いて省ける共通カラム。
   *
   * <p>規約が省いてよいとするのは更新とデータパッチのカラムだけなので、{@code lock_no}は省けない。
   */
  private static final Set<String> WORK_TABLE_OMITTABLE =
      Set.of(
          "updated_at",
          "updated_by",
          "updated_pgm_cd",
          "updated_tx_id",
          "patched_at",
          "patched_by",
          "patched_id");

  private ChangesetCommonColumnRules() {
    // 静的メソッドだけを持つ。
  }

  /**
   * 1つの業務テーブルの共通カラムを判定する。
   *
   * @param table 業務テーブルの最終形
   * @return 違反
   */
  /* package */ static List<ConventionViolation> check(final TableShape table) {
    final List<ConventionViolation> violations = new ArrayList<>();
    final boolean workTable = table.name().startsWith("w_");
    boolean omittedWithoutComment = false;
    for (final Map.Entry<String, ColumnShape> spec : COMMON_COLUMNS) {
      final String name = spec.getKey();
      final ColumnShape actual = table.columns().get(name);
      final String target = table.qualifiedName() + "." + name;
      if (actual == null) {
        if (workTable && WORK_TABLE_OMITTABLE.contains(name)) {
          omittedWithoutComment |= !table.createdWithComment();
        } else {
          violations.add(commonColumn(target, "共通カラムがない"));
        }
      } else {
        shape(target, spec.getValue(), actual, violations);
      }
    }
    if (omittedWithoutComment) {
      violations.add(
          new ConventionViolation(
              "C2",
              table.qualifiedName(),
              "ワークテーブルで更新とデータパッチの共通カラムを省くときは、理由をテーブルを作るchangesetのcommentに書く（"
                  + COMMON_COLUMNS_DOC
                  + "）。"));
    }
    return violations;
  }

  private static void shape(
      final String target,
      final ColumnShape expected,
      final ColumnShape actual,
      final List<ConventionViolation> out) {
    if (!expected.type().equals(actual.type())) {
      out.add(commonColumn(target, "型を" + expected.type() + "にする（現在は" + actual.type() + "）"));
    }
    if (expected.nullable() != actual.nullable()) {
      out.add(commonColumn(target, expected.nullable() ? "NULLを許す" : "NOT NULLにする"));
    }
    if (actual.hasDefault()) {
      out.add(
          new ConventionViolation(
              "C3", target, "共通カラムに既定値を付けず、アプリケーションがバインドする（" + COMMON_COLUMNS_DOC + "）。"));
    }
  }

  private static ConventionViolation commonColumn(final String target, final String detail) {
    return new ConventionViolation("C1", target, detail + "（" + COMMON_COLUMNS_DOC + "）。");
  }

  private static ColumnShape required(final String type) {
    return new ColumnShape(type, false, false);
  }
}
