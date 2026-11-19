package com.example.demo.tenancy.domain.model;

/**
 * IdP が認証した利用者の識別。ID トークンの {@code iss} と {@code sub} の組で、利用者を引く鍵になる。
 *
 * @param issuer IdP の発行者の URL（{@code iss}）
 * @param subject 発行者の中で利用者を一意に表す値（{@code sub}）
 */
public record ExternalIdentity(String issuer, String subject) {

  /** 列の長さの上限。OIDC は {@code sub} を 255 文字以下に定めている。 */
  private static final int MAX_LENGTH = 255;

  /** 発行者と subject が、空でなく上限以下であることを確かめる。 */
  public ExternalIdentity {
    require("issuer", issuer);
    require("subject", subject);
  }

  private static void require(final String name, final String value) {
    if (value.isBlank() || value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          name + " must be 1 to " + MAX_LENGTH + " characters: length=" + value.length());
    }
  }
}
