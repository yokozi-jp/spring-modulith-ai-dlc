package com.example.demo.shared.idempotency;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

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
   * 要求のヘッダーの行からキーを読む。
   *
   * <p>同じ名前の行が 2 つ以上あれば、値が同じでも形式の誤りにする。RFC 9110 5.3 では行を {@code ,} でつないだ一覧と同じ意味になり、1 つの String
   * ではないためである。{@link UUID#fromString} は桁の足りない値も受けるため、8-4-4-4-12 の形を先に確かめる。
   *
   * @param lines ヘッダーの行の値（{@code "8e03978e-40d5-43e8-bc93-6894a57f9324"} のように引用符を含む）。ヘッダーがなければ
   *     {@code null} か空
   * @throws IdempotencyKeyException ヘッダーがない場合（{@code MISSING}）、1 行の引用符で囲んだ UUID でない場合（{@code
   *     INVALID}）
   */
  public static IdempotencyKey fromHeader(final String @Nullable ... lines) {
    if (lines == null || lines.length == 0) {
      throw new IdempotencyKeyException(
          IdempotencyKeyException.Reason.MISSING, "Idempotency-Key header is missing");
    }
    final Matcher matcher = QUOTED_UUID.matcher(lines[0]);
    if (lines.length > 1 || !matcher.matches()) {
      throw new IdempotencyKeyException(
          IdempotencyKeyException.Reason.INVALID,
          "Idempotency-Key must be one header line of a quoted UUID: lines=" + lines.length);
    }
    return new IdempotencyKey(UUID.fromString(matcher.group(1)));
  }
}
