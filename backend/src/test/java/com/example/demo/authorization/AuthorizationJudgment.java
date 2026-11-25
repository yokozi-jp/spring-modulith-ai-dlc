package com.example.demo.authorization;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 認可の表の 1 つの組の観測が、期待した結果に合うかを判定する（ADR-129）。
 *
 * <p>期待と観測の値だけを受け取り、要求を送らず、DB も読まない。アプリの組と、テストの側の fixture の Controller の両方を同じ判定にかける。
 */
final class AuthorizationJudgment {

  /** 本文を比べる前に消す、要求ごとに変わる項目。 */
  private static final List<String> VOLATILE_FIELDS = List.of("traceId");

  /** 本文を正規化する。 */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AuthorizationJudgment() {}

  /** 期待する結果を確かめるのに、対象が存在しないときの応答が要るか。見えないことは、その応答と比べて確かめる。 */
  /* package */ static boolean needsAbsentReference(final AuthorizationOutcome expected) {
    return expected == AuthorizationOutcome.HIDDEN;
  }

  /** 業務の行が変わらないことを確かめるか。拒否を期待する書き込みだけを確かめる。読み取りは行を変えない。 */
  /* package */ static boolean checksRows(
      final AuthorizationOperation operation, final AuthorizationOutcome expected) {
    return operation.writes() && expected != AuthorizationOutcome.ALLOWED;
  }

  /**
   * 期待と違う点を返す。空なら期待どおりである。
   *
   * @param expectation 期待
   * @param observation 観測した応答と業務の行
   * @return 期待と違う点
   */
  /* package */ static List<Violation> violations(
      final Expectation expectation, final Observation observation) {
    final Exchange actual = observation.actual();
    final List<Violation> violations =
        new ArrayList<>(
            switch (expectation.outcome()) {
              case ALLOWED -> allowed(expectation.body(), actual);
              case HIDDEN -> hidden(expectation.absentStatus(), actual, observation.absent());
              case FORBIDDEN -> status(403, actual);
              case UNPROCESSABLE -> status(422, actual);
            });
    if (expectation.outcome() != AuthorizationOutcome.ALLOWED
        && !observation.rowsBefore().equals(observation.rowsAfter())) {
      violations.add(
          new Violation(
              Kind.ROWS_CHANGED,
              "消えた行="
                  + minus(observation.rowsBefore(), observation.rowsAfter())
                  + ", 増えた行="
                  + minus(observation.rowsAfter(), observation.rowsBefore())));
    }
    return violations;
  }

  private static List<Violation> allowed(final BodyExpectation body, final Exchange actual) {
    final List<Violation> violations = new ArrayList<>();
    if (actual.status() < 200 || actual.status() >= 300) {
      violations.add(new Violation(Kind.WRONG_STATUS, "2xx を期待した: actual=" + actual));
    }
    final List<String> missing =
        body.includes().stream().filter(id -> !actual.body().contains(id)).toList();
    if (!missing.isEmpty()) {
      violations.add(
          new Violation(Kind.MISSING_FROM_BODY, "本文にない=" + missing + ", actual=" + actual));
    }
    final List<String> leaked = body.excludes().stream().filter(actual.body()::contains).toList();
    if (!leaked.isEmpty()) {
      violations.add(
          new Violation(Kind.LEAKED_INTO_BODY, "本文に出た=" + leaked + ", actual=" + actual));
    }
    return violations;
  }

  private static List<Violation> hidden(
      final int absentStatus, final Exchange actual, final @Nullable Exchange absent) {
    if (absent == null) {
      throw new IllegalArgumentException("見えないことを確かめるには、対象が存在しないときの応答が要る: actual=" + actual);
    }
    final List<Violation> violations = new ArrayList<>();
    if (absent.status() != absentStatus) {
      violations.add(
          new Violation(
              Kind.ABSENT_NOT_NOT_FOUND,
              "存在しないときの応答が " + absentStatus + " でない: 存在しないとき=" + absent));
    }
    if (!actual.equals(absent)) {
      violations.add(
          new Violation(Kind.NOT_SAME_AS_ABSENT, "actual=" + actual + ", 存在しないとき=" + absent));
    }
    return violations;
  }

  private static List<Violation> status(final int expected, final Exchange actual) {
    return actual.status() == expected
        ? List.of()
        : List.of(new Violation(Kind.WRONG_STATUS, expected + " を期待した: actual=" + actual));
  }

  private static List<String> minus(final List<String> rows, final List<String> others) {
    return rows.stream().filter(row -> !others.contains(row)).toList();
  }

  /** 期待と違う点の種類。 */
  /* package */ enum Kind {
    /** 期待した status でない。 */
    WRONG_STATUS,
    /** 許した本文に、見えるはずのものがない。拒否しすぎである。 */
    MISSING_FROM_BODY,
    /** 許した本文に、見えてはいけないものがある。 */
    LEAKED_INTO_BODY,
    /** 対象が存在しないときの応答が、見つからないときの形（404 か、一覧の 200）でない。 */
    ABSENT_NOT_NOT_FOUND,
    /** 対象が存在しないときと、status か本文が違う。 */
    NOT_SAME_AS_ABSENT,
    /** 拒否したのに業務の行が変わった。 */
    ROWS_CHANGED
  }

  /**
   * 期待と違う点。
   *
   * @param kind 種類
   * @param detail 失敗の診断に出す、応答と行の中身
   */
  /* package */ record Violation(Kind kind, String detail) {}

  /**
   * 1 つの組に期待すること。
   *
   * @param outcome 期待する結果
   * @param absentStatus 対象が存在しないときの status。{@link AuthorizationOutcome#HIDDEN} を期待する組だけが使う
   * @param body 許した本文が見せるもの。{@link AuthorizationOutcome#ALLOWED} を期待する組だけが使う
   */
  /* package */ record Expectation(
      AuthorizationOutcome outcome, int absentStatus, BodyExpectation body) {}

  /**
   * 許した本文が持つべき ID と、持ってはいけない ID。
   *
   * @param includes 本文に出るべき ID
   * @param excludes 本文に出てはいけない ID
   */
  /* package */ record BodyExpectation(List<String> includes, List<String> excludes) {

    /** 本文を確かめない。 */
    /* package */ static final BodyExpectation NONE = new BodyExpectation(List.of(), List.of());
  }

  /**
   * 応答の status と、要求ごとに変わる項目を消した本文。
   *
   * @param status HTTP の status
   * @param body 正規化した本文
   */
  /* package */ record Exchange(int status, String body) {

    /** 本文が JSON のオブジェクトなら {@link #VOLATILE_FIELDS} を消す。JSON でない本文（HTML や文字列）はそのまま持つ。 */
    /* package */ static Exchange from(final int status, final String body) {
      if (body.isBlank()) {
        return new Exchange(status, "");
      }
      final JsonNode json;
      try {
        json = JSON.readTree(body);
      } catch (JacksonException e) {
        return new Exchange(status, body);
      }
      if (json instanceof ObjectNode object) {
        object.remove(VOLATILE_FIELDS);
      }
      return new Exchange(status, json.toString());
    }
  }

  /**
   * 1 つの組の観測。
   *
   * @param actual 対象への要求の応答
   * @param absent 同じ要求を、対象が存在しないときに送った応答。{@link #needsAbsentReference} の組だけが持つ
   * @param rowsBefore 要求の前の業務の行。{@link #checksRows} でない組では空
   * @param rowsAfter 要求の後の業務の行。{@link #checksRows} でない組では空
   */
  /* package */ record Observation(
      Exchange actual, @Nullable Exchange absent, List<String> rowsBefore, List<String> rowsAfter) {

    /** 行を並べ替えて持つ。 */
    /* package */ Observation {
      rowsBefore = rowsBefore.stream().sorted().toList();
      rowsAfter = rowsAfter.stream().sorted().toList();
    }
  }
}
