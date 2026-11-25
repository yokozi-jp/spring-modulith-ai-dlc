package com.example.demo.shared.idempotency;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * クライアントが利用者の操作ごとに採番する {@code Idempotency-Key} ヘッダーの値（ADR-126）。
 *
 * @param value キーの UUID
 */
public record IdempotencyKey(UUID value) {

  /** ヘッダーの名前。 */
  public static final String HEADER = "Idempotency-Key";

  /**
   * 引用符で囲んだ UUID の Structured Field の String（draft-ietf-httpapi-idempotency-key-header-08 の 2.1）。
   */
  private static final Pattern QUOTED_UUID =
      Pattern.compile(
          "\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"");

  /**
   * ヘッダーの値を読む。Spring MVC が {@code @RequestHeader} の値を変換するときに呼ぶ。
   *
   * <p>{@link UUID#fromString} は桁の足りない値も受けるため、8-4-4-4-12 の形を先に確かめる。
   *
   * @param header ヘッダーの値（{@code "8e03978e-40d5-43e8-bc93-6894a57f9324"} のように引用符を含む）
   * @throws IllegalArgumentException 引用符で囲んだ UUID でない場合
   */
  public static IdempotencyKey valueOf(final String header) {
    final Matcher matcher = QUOTED_UUID.matcher(header);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Idempotency-Key must be a quoted UUID");
    }
    return new IdempotencyKey(UUID.fromString(matcher.group(1)));
  }
}
