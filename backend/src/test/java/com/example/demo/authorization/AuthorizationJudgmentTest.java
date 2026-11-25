package com.example.demo.authorization;

import static com.example.demo.authorization.AuthorizationJudgment.Kind.ABSENT_NOT_NOT_FOUND;
import static com.example.demo.authorization.AuthorizationJudgment.Kind.LEAKED_INTO_BODY;
import static com.example.demo.authorization.AuthorizationJudgment.Kind.MISSING_FROM_BODY;
import static com.example.demo.authorization.AuthorizationJudgment.Kind.NOT_SAME_AS_ABSENT;
import static com.example.demo.authorization.AuthorizationJudgment.Kind.ROWS_CHANGED;
import static com.example.demo.authorization.AuthorizationJudgment.Kind.WRONG_STATUS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import authorizationfixture.NoteFixtureController;
import com.example.demo.authorization.AuthorizationJudgment.BodyExpectation;
import com.example.demo.authorization.AuthorizationJudgment.Exchange;
import com.example.demo.authorization.AuthorizationJudgment.Expectation;
import com.example.demo.authorization.AuthorizationJudgment.Kind;
import com.example.demo.authorization.AuthorizationJudgment.Observation;
import com.example.demo.authorization.AuthorizationJudgment.Violation;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 認可の表の判定が、4 つの結果のそれぞれで期待どおりの組を通し、違う組を違反の種類で検出することを確かめる（ADR-129）。
 *
 * <p>判定は値だけを見るので、表の case は値から作る。持ち主で絞り忘れた fixture の Controller は、Spring の context を起動せず MockMvc
 * で呼ぶ。
 */
// 補助のメソッドは、Exception を宣言する MockMvc.perform を呼ぶ。表の case は違反の種類を静的 import で読む。
@SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.TooManyStaticImports"})
class AuthorizationJudgmentTest {

  /** メモの持ち主。 */
  private static final String OWNER = "owner";

  /** 持ち主でない、メモを要求する人。 */
  private static final String INTRUDER = "intruder";

  /** 本文に出るべき ID。 */
  private static final String OWN_ID = "own-id";

  /** 本文に出てはいけない ID。 */
  private static final String FOREIGN_ID = "foreign-id";

  /** 存在しない対象への 404 の本文。 */
  private static final String NOT_FOUND = "{\"title\":\"Not Found\",\"traceId\":\"a\"}";

  /** 行を確かめない観測の行。 */
  private static final List<String> NO_ROWS = List.of();

  private static Stream<Arguments> cases() {
    final BodyExpectation ownOnly = new BodyExpectation(List.of(OWN_ID), List.of(FOREIGN_ID));
    return Stream.of(
        Arguments.of(
            "ALLOWED: 自分のものだけを見せる 200",
            allowed(ownOnly),
            plain(200, "{\"items\":[\"own-id\"]}"),
            List.of()),
        Arguments.of(
            "ALLOWED: 本文のない 204", allowed(BodyExpectation.NONE), plain(204, ""), List.of()),
        Arguments.of(
            "ALLOWED: 403",
            allowed(ownOnly),
            plain(403, "{\"items\":[\"own-id\"]}"),
            List.of(WRONG_STATUS)),
        Arguments.of(
            "ALLOWED: 空の一覧は拒否しすぎ",
            allowed(ownOnly),
            plain(200, "{\"items\":[]}"),
            List.of(MISSING_FROM_BODY)),
        Arguments.of(
            "ALLOWED: 他人のものが出る",
            allowed(ownOnly),
            plain(200, "{\"items\":[\"own-id\",\"foreign-id\"]}"),
            List.of(LEAKED_INTO_BODY)),
        Arguments.of(
            "HIDDEN: 存在しないときと同じ 404（traceId は比べない）",
            hidden(404),
            withAbsent(404, "{\"title\":\"Not Found\",\"traceId\":\"b\"}", 404, NOT_FOUND),
            List.of()),
        Arguments.of(
            "HIDDEN: 一覧が存在しないときと同じ空の 200",
            hidden(200),
            withAbsent(200, "{\"items\":[]}", 200, "{\"items\":[]}"),
            List.of()),
        Arguments.of(
            "HIDDEN: 見えてしまう 200",
            hidden(404),
            withAbsent(200, "{\"text\":\"secret\"}", 404, NOT_FOUND),
            List.of(NOT_SAME_AS_ABSENT)),
        Arguments.of(
            "HIDDEN: 存在しないときと本文が違う 404",
            hidden(404),
            withAbsent(404, "{\"title\":\"Not Found\",\"detail\":\"exists\"}", 404, NOT_FOUND),
            List.of(NOT_SAME_AS_ABSENT)),
        Arguments.of(
            "HIDDEN: どちらも 500 なら見つからない形でない",
            hidden(404),
            withAbsent(500, "{}", 500, "{}"),
            List.of(ABSENT_NOT_NOT_FOUND)),
        Arguments.of(
            "HIDDEN: どちらも 403 なら見つからない形でない",
            hidden(200),
            withAbsent(403, "{}", 403, "{}"),
            List.of(ABSENT_NOT_NOT_FOUND)),
        Arguments.of(
            "HIDDEN: 404 の裏で行が変わる",
            hidden(404),
            new Observation(
                Exchange.from(404, NOT_FOUND),
                Exchange.from(404, NOT_FOUND),
                List.of("note=old"),
                List.of("note=new")),
            List.of(ROWS_CHANGED)),
        Arguments.of(
            "FORBIDDEN: 403", outcome(AuthorizationOutcome.FORBIDDEN), plain(403, "{}"), List.of()),
        Arguments.of(
            "FORBIDDEN: 200",
            outcome(AuthorizationOutcome.FORBIDDEN),
            plain(200, "{}"),
            List.of(WRONG_STATUS)),
        Arguments.of(
            "FORBIDDEN: 404",
            outcome(AuthorizationOutcome.FORBIDDEN),
            plain(404, NOT_FOUND),
            List.of(WRONG_STATUS)),
        Arguments.of(
            "FORBIDDEN: 403 の裏で行が変わる",
            outcome(AuthorizationOutcome.FORBIDDEN),
            new Observation(Exchange.from(403, "{}"), null, List.of("a"), List.of("a", "b")),
            List.of(ROWS_CHANGED)),
        Arguments.of(
            "UNPROCESSABLE: 422",
            outcome(AuthorizationOutcome.UNPROCESSABLE),
            plain(422, "{}"),
            List.of()),
        Arguments.of(
            "UNPROCESSABLE: 201",
            outcome(AuthorizationOutcome.UNPROCESSABLE),
            plain(201, ""),
            List.of(WRONG_STATUS)),
        Arguments.of(
            "UNPROCESSABLE: 403",
            outcome(AuthorizationOutcome.UNPROCESSABLE),
            plain(403, "{}"),
            List.of(WRONG_STATUS)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("判定は、期待どおりの組を通し、違う組を違反の種類で検出する")
  void judgesEachOutcome(
      final String name,
      final Expectation expectation,
      final Observation observation,
      final List<Kind> expected) {
    assertThat(AuthorizationJudgment.violations(expectation, observation))
        .as("%s: %s", name, observation)
        .extracting(Violation::kind)
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  @DisplayName("JSON でない本文（HTML や文字列）は、そのままの文字列で比べる")
  void nonJsonBodyIsKeptAsText() {
    assertThat(Exchange.from(500, "<html><body>error</body></html>"))
        .as("HTML の本文")
        .isEqualTo(new Exchange(500, "<html><body>error</body></html>"));
    assertThat(Exchange.from(404, "not found"))
        .as("文字列の本文")
        .isEqualTo(new Exchange(404, "not found"));
  }

  private static Expectation allowed(final BodyExpectation body) {
    return new Expectation(AuthorizationOutcome.ALLOWED, 404, body);
  }

  private static Expectation hidden(final int absentStatus) {
    return new Expectation(AuthorizationOutcome.HIDDEN, absentStatus, BodyExpectation.NONE);
  }

  private static Expectation outcome(final AuthorizationOutcome outcome) {
    return new Expectation(outcome, 404, BodyExpectation.NONE);
  }

  private static Observation plain(final int status, final String body) {
    return new Observation(Exchange.from(status, body), null, NO_ROWS, NO_ROWS);
  }

  private static Observation withAbsent(
      final int status, final String body, final int absentStatus, final String absentBody) {
    return new Observation(
        Exchange.from(status, body), Exchange.from(absentStatus, absentBody), NO_ROWS, NO_ROWS);
  }

  /** 持ち主で絞り忘れた fixture の Controller の応答を、判定にかける。 */
  /* package */ @Nested
  @DisplayName("fixture の Controller")
  class FixtureControllerTest {

    /** メモを持つ fixture の Controller。 */
    private final NoteFixtureController notes = new NoteFixtureController();

    /** fixture の Controller だけを通すクライアント。 */
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(notes).build();

    @Test
    @DisplayName("持ち主で絞らない GET が他人のメモを返すと、存在しないメモと応答が違う違反になる")
    void leakingReadIsViolation() throws Exception {
      assertThat(intruderViolations(HttpMethod.GET, "/leaky/{id}"))
          .as("他人に見える GET の違反")
          .extracting(Violation::kind)
          .containsExactly(NOT_SAME_AS_ABSENT);
    }

    @Test
    @DisplayName("404 を返すが他人のメモを書き換える PUT は、応答が同じでも業務の行が変わった違反になる")
    void mutatingWriteBehindNotFoundIsViolation() throws Exception {
      assertThat(intruderViolations(HttpMethod.PUT, "/mutating/{id}"))
          .as("404 の裏で書き換える PUT の違反")
          .extracting(Violation::kind)
          .containsExactly(ROWS_CHANGED);
    }

    @Test
    @DisplayName("持ち主で絞る GET と PUT は、他人に存在しないメモと同じ 404 を返し、行を変えないので違反がない")
    void scopedReadAndWriteHaveNoViolation() throws Exception {
      assertThat(intruderViolations(HttpMethod.GET, "/scoped/{id}")).as("持ち主で絞る GET").isEmpty();
      assertThat(intruderViolations(HttpMethod.PUT, "/scoped/{id}")).as("持ち主で絞る PUT").isEmpty();
    }

    /** 持ち主のメモに、持ち主でない人が要求を送り、見えないこと（{@link AuthorizationOutcome#HIDDEN}）を期待して判定する。 */
    private List<Violation> intruderViolations(final HttpMethod method, final String path)
        throws Exception {
      final UUID note = notes.add(OWNER, "持ち主のメモ");
      final Exchange absent = AuthorizationScene.exchange(perform(method, path, UUID.randomUUID()));
      final List<String> rowsBefore = notes.rows();
      final Exchange actual = AuthorizationScene.exchange(perform(method, path, note));
      return AuthorizationJudgment.violations(
          hidden(404), new Observation(actual, absent, rowsBefore, notes.rows()));
    }

    private MvcResult perform(final HttpMethod method, final String path, final UUID id)
        throws Exception {
      return mockMvc
          .perform(
              request(method, NoteFixtureController.PATH + path, id)
                  .header(NoteFixtureController.USER_HEADER, INTRUDER)
                  .contentType(MediaType.TEXT_PLAIN)
                  .content("書き換えた本文"))
          .andReturn();
    }
  }
}
