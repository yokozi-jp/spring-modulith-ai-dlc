package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import authorizationfixture.NoteFixtureController;
import com.example.demo.AuthorizationJudgment.Exchange;
import com.example.demo.AuthorizationJudgment.Observation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 認可の表の判定を、持ち主で絞り忘れた fixture の Controller にかけ、違反を検出することを確かめる（ADR-129）。
 *
 * <p>判定は観測の値だけを見るので、Spring の context を起動せず、fixture の Controller だけを MockMvc で呼ぶ。
 */
// 補助のメソッドは、Exception を宣言する MockMvc.perform を呼ぶ。
@SuppressWarnings("PMD.SignatureDeclareThrowsException")
class AuthorizationJudgmentTest {

  /** メモの持ち主。 */
  private static final String OWNER = "owner";

  /** 持ち主でない、メモを要求する人。 */
  private static final String INTRUDER = "intruder";

  /** メモを持つ fixture の Controller。 */
  private final NoteFixtureController notes = new NoteFixtureController();

  /** fixture の Controller だけを通すクライアント。 */
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(notes).build();

  @Test
  @DisplayName("持ち主で絞らない GET が他人のメモを返すと、存在しないメモと応答が違う違反になる")
  void leakingReadIsViolation() throws Exception {
    assertThat(intruderViolations(HttpMethod.GET, "/leaky/{id}")).as("他人に見える GET の違反").hasSize(1);
  }

  @Test
  @DisplayName("404 を返すが他人のメモを書き換える PUT は、応答が同じでも業務の行が変わった違反になる")
  void mutatingWriteBehindNotFoundIsViolation() throws Exception {
    assertThat(intruderViolations(HttpMethod.PUT, "/mutating/{id}"))
        .as("404 の裏で書き換える PUT の違反")
        .singleElement()
        .asString()
        .startsWith("拒否を期待したが、業務の行が変わった");
  }

  @Test
  @DisplayName("持ち主で絞る GET と PUT は、他人に存在しないメモと同じ 404 を返し、行を変えないので違反がない")
  void scopedReadAndWriteHaveNoViolation() throws Exception {
    assertThat(intruderViolations(HttpMethod.GET, "/scoped/{id}")).as("持ち主で絞る GET").isEmpty();
    assertThat(intruderViolations(HttpMethod.PUT, "/scoped/{id}")).as("持ち主で絞る PUT").isEmpty();
  }

  /** 持ち主のメモに、持ち主でない人が要求を送り、見えないこと（{@link AuthorizationOutcome#HIDDEN}）を期待して判定する。 */
  private List<String> intruderViolations(final HttpMethod method, final String path)
      throws Exception {
    final UUID note = notes.add(OWNER, "持ち主のメモ");
    final Exchange absent = Exchange.from(perform(method, path, UUID.randomUUID()));
    final List<String> rowsBefore = notes.rows();
    final Exchange actual = Exchange.from(perform(method, path, note));
    return AuthorizationJudgment.violations(
        AuthorizationOutcome.HIDDEN, new Observation(actual, absent, rowsBefore, notes.rows()));
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
