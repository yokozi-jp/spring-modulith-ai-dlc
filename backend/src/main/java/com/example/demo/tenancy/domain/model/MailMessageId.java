package com.example.demo.tenancy.domain.model;

/**
 * 送信のサービスが採番したメールの識別子。
 *
 * <p>宛先をログに出さないため、送ったメールを送信のサービスの記録と突き合わせるときは、この識別子を使う。
 *
 * @param value 空白だけでない識別子
 */
public record MailMessageId(String value) {

  /** 識別子が空白だけでないことを確かめる。 */
  public MailMessageId {
    if (value.isBlank()) {
      throw new IllegalArgumentException("mailMessageId must not be blank");
    }
  }
}
