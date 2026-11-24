package com.example.demo.mail.domain.model;

/**
 * メールの宛先のアドレス。
 *
 * <p>形式は入力を受ける境界で検査し、ここでは空白だけの値だけを拒否する。届くかどうかは送信のサービスが判定する。
 *
 * @param value 空白だけでないアドレス
 */
public record MailRecipient(String value) {

  /** アドレスが空白だけでないことを確かめる。アドレスはログに出さないため、例外のメッセージに値を入れない。 */
  public MailRecipient {
    if (value.isBlank()) {
      throw new IllegalArgumentException("mailRecipient must not be blank");
    }
  }

  /** 宛先のアドレスはログに出さないため（ADR-015）、値を伏せる。 */
  @Override
  public String toString() {
    return "MailRecipient[value=***]";
  }
}
