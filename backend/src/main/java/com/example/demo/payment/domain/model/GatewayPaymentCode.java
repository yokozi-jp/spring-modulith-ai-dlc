package com.example.demo.payment.domain.model;

/**
 * 決済代行が採番した決済の識別子。
 *
 * <p>検査に失敗するのは決済代行の応答の誤りであり、業務上の失敗ではないため {@link IllegalArgumentException} を投げる。
 *
 * @param value 空白だけでない、64 文字以下の識別子
 */
public record GatewayPaymentCode(String value) {

  /** 列の {@code varchar(64)} に合わせた上限。 */
  private static final int MAX_LENGTH = 64;

  /** 識別子が空白だけでなく、64 文字以下であることを確かめる。 */
  public GatewayPaymentCode {
    if (value.isBlank()) {
      throw new IllegalArgumentException("gatewayPaymentCode must not be blank");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "gatewayPaymentCode is too long: length=" + value.length());
    }
  }
}
