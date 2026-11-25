package com.example.demo.testkit;

import com.example.demo.shared.idempotency.IdempotencyKey;
import java.util.UUID;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 更新の要求と Command に付ける {@code Idempotency-Key} を作る補助（ADR-126）。 */
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
}
