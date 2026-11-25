package com.example.demo.testkit;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.shared.idempotency.IdempotencyKey;
import java.util.UUID;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 更新の要求と Command に付ける {@code Idempotency-Key} を作り、キーの problem を確かめる補助（ADR-126）。 */
public final class IdempotencyKeys {

  private IdempotencyKeys() {}

  /** 新しいキーを返す。 */
  public static IdempotencyKey next() {
    return new IdempotencyKey(UUID.randomUUID());
  }

  /** 新しいキーを、MockMvc の要求の {@code Idempotency-Key} ヘッダーに付ける。 */
  public static RequestPostProcessor fresh() {
    return header(next());
  }

  /** キーを、クライアントと同じく引用符で囲んだ UUID の形で、MockMvc の要求のヘッダーに付ける。 */
  public static RequestPostProcessor header(final IdempotencyKey key) {
    return request -> {
      request.addHeader(IdempotencyKey.HEADER, "\"" + key.value() + "\"");
      return request;
    };
  }

  /**
   * 応答が Idempotency-Key の problem で、状態コード、type、status、title を持ち、detail を持たないことを確かめる。
   *
   * @param actions MockMvc の応答
   * @param status 状態コード
   * @param type {@code /problems/} より後の problem type
   * @return 続けて期待値を書ける応答
   */
  // ResultActions#andExpect が Exception を投げるため。
  @SuppressWarnings("PMD.SignatureDeclareThrowsException")
  public static ResultActions expectIdempotencyProblem(
      final ResultActions actions, final int status, final String type) throws Exception {
    return actions
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.type").value("/problems/" + type))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.title").exists())
        .andExpect(jsonPath("$.detail").doesNotExist());
  }
}
