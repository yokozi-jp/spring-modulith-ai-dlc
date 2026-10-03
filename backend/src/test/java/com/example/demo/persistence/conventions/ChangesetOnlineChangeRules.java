package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.Changeset.Change;
import com.example.demo.persistence.conventions.ChangesetReplay.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 前のchangesetで作ったテーブルへの、ロックを伴う変更の規則（M7、M8）を判定する。
 *
 * <p>Liquibase OSS 5.0.4の{@code createIndex}には{@code CONCURRENTLY}を指定する属性がない。そのため{@code
 * createIndex}は、同じchangesetの{@code createTable}で作ったテーブルにだけ使える。{@code modulith}スキーマへの変更は判定しない。
 */
final class ChangesetOnlineChangeRules {

  /** ロックを抑えるスキーマ変更の文書。 */
  private static final String ONLINE_CHANGE_DOC =
      "docs/database/postgresql-online-schema-change.md";

  /** 任意のSQLの{@code CREATE INDEX}。グループは順に、CONCURRENTLY、インデックス名、ON ONLY、テーブル。 */
  private static final Pattern SQL_CREATE_INDEX =
      Pattern.compile(
          "\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?"
              + "(?:([a-z0-9_\"]+)\\s+)?ON\\s+(ONLY\\s+)?([a-z0-9_.\"]+)",
          Pattern.CASE_INSENSITIVE);

  private ChangesetOnlineChangeRules() {
    // 静的メソッドだけを持つ。
  }

  /**
   * 業務テーブルへの変更を判定する。
   *
   * @param changesets ファイル名の順に並べたchangeset
   * @param replay changesetを適用した結果
   * @return 違反
   */
  /* package */ static List<ConventionViolation> check(
      final List<Changeset> changesets, final Result replay) {
    final List<ConventionViolation> violations = new ArrayList<>();
    for (final Changeset changeset : changesets) {
      final String id = changeset.changesetId();
      for (final Change change : changeset.changes()) {
        if (ChangesetLint.isBusinessSchema(change.schema())) {
          onlineChange(id, change, replay, violations);
        }
        change.rawSql().ifPresent(sql -> sqlIndexes(id, sql, replay, violations));
      }
    }
    return violations;
  }

  /** M7、M8：前のchangesetで作ったテーブルへの、ロックを伴う変更。 */
  private static void onlineChange(
      final String changesetId,
      final Change change,
      final Result replay,
      final List<ConventionViolation> out) {
    final String schema = change.schema();
    final String table = ChangesetReplay.qualify(schema, change.table());
    if (replay.createdIn(changesetId, table)) {
      return;
    }
    switch (change.type()) {
      case "createIndex" ->
          out.add(withoutConcurrently(ChangesetReplay.qualify(schema, change.text("indexName"))));
      case "addNotNullConstraint" ->
          out.add(
              new ConventionViolation(
                  "M8",
                  table + "." + change.text("columnName"),
                  "既存のテーブルへNOT NULLを直接追加しない。NOT VALIDのCHECK制約を経由する（" + ONLINE_CHANGE_DOC + "）。"));
      default -> {
        // ロックを伴う変更として判定するのは、この2つのChange Typeだけである。
      }
    }
  }

  /** M7：任意のSQLで、前のchangesetで作ったテーブルに{@code CONCURRENTLY}なしでインデックスを作る。 */
  private static void sqlIndexes(
      final String changesetId,
      final String sql,
      final Result replay,
      final List<ConventionViolation> out) {
    final Matcher matcher = SQL_CREATE_INDEX.matcher(sql);
    while (matcher.find()) {
      final String qualified = matcher.group(4).replace("\"", "").toLowerCase(Locale.ROOT);
      final int dot = qualified.indexOf('.');
      final String schema = dot < 0 ? "" : qualified.substring(0, dot);
      final String table = qualified.substring(dot + 1);
      final boolean allowed =
          matcher.group(1) != null
              || matcher.group(3) != null
              || !ChangesetLint.isBusinessSchema(schema)
              || replay.createdIn(changesetId, ChangesetReplay.qualify(schema, table));
      if (!allowed) {
        final String index = Optional.ofNullable(matcher.group(2)).orElse(table).replace("\"", "");
        out.add(withoutConcurrently(ChangesetReplay.qualify(schema, index)));
      }
    }
  }

  private static ConventionViolation withoutConcurrently(final String index) {
    return new ConventionViolation(
        "M7",
        index,
        "既存のテーブルへのインデックスは、runInTransaction: falseのchangesetでCREATE INDEX CONCURRENTLYを使って作る（"
            + ONLINE_CHANGE_DOC
            + "）。");
  }
}
