package com.example.demo;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 認可の表の 1 つの組の観測が、期待した結果に合うかを判定する（ADR-129）。
 *
 * <p>観測の値だけを受け取り、Spring も DB も使わない。アプリの組と、テストの側の fixture の Controller の両方を同じ判定にかける。
 */
final class AuthorizationJudgment {

  /** 本文を比べる前に消す、要求ごとに変わる項目。 */
  private static final List<String> VOLATILE_FIELDS = List.of("traceId");

  /** 本文を正規化する。 */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AuthorizationJudgment() {}

  /**
   * 期待と違う点を返す。空なら期待どおりである。
   *
   * @param expected 期待する結果
   * @param observation 観測した応答と業務の行
   * @return 期待と違う点の説明
   */
  /* package */ static List<String> violations(
      final AuthorizationOutcome expected, final Observation observation) {
    final List<String> violations = new ArrayList<>();
    final Exchange actual = observation.actual();
    final boolean matches =
        switch (expected) {
          case ALLOWED -> actual.status() >= 200 && actual.status() < 300;
          case HIDDEN -> actual.equals(absentOf(observation));
          case FORBIDDEN -> actual.status() == 403;
          case UNPROCESSABLE -> actual.status() == 422;
        };
    if (!matches) {
      violations.add(
          expected
              + " を期待したが、応答が違う: actual="
              + actual
              + (expected == AuthorizationOutcome.HIDDEN
                  ? ", 存在しないとき=" + observation.absent()
                  : ""));
    }
    if (expected != AuthorizationOutcome.ALLOWED
        && !observation.rowsBefore().equals(observation.rowsAfter())) {
      violations.add(
          "拒否を期待したが、業務の行が変わった: 消えた行="
              + minus(observation.rowsBefore(), observation.rowsAfter())
              + ", 増えた行="
              + minus(observation.rowsAfter(), observation.rowsBefore()));
    }
    return violations;
  }

  private static Exchange absentOf(final Observation observation) {
    final Exchange absent = observation.absent();
    if (absent == null) {
      throw new IllegalArgumentException("見えないことを確かめるには、対象が存在しないときの応答が要る: " + observation);
    }
    return absent;
  }

  private static List<String> minus(final List<String> rows, final List<String> others) {
    return rows.stream().filter(row -> !others.contains(row)).toList();
  }

  /**
   * 応答の status と、要求ごとに変わる項目を消した本文。
   *
   * @param status HTTP の status
   * @param body 正規化した本文
   */
  /* package */ record Exchange(int status, String body) {

    /** MockMvc の応答を {@link #from(int, String)} で比べられる形にする。 */
    /* package */ static Exchange from(final MvcResult result) throws UnsupportedEncodingException {
      return from(
          result.getResponse().getStatus(),
          result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 本文が JSON のオブジェクトなら {@link #VOLATILE_FIELDS} を消し、比べられる形にする。 */
    /* package */ static Exchange from(final int status, final String body) {
      if (body.isBlank()) {
        return new Exchange(status, "");
      }
      final JsonNode json = JSON.readTree(body);
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
   * @param absent 同じ要求を、対象が存在しないときに送った応答。{@link AuthorizationOutcome#HIDDEN} を期待する組だけが持つ
   * @param rowsBefore 要求の前の業務の行。読み取りの操作では空
   * @param rowsAfter 要求の後の業務の行。読み取りの操作では空
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
