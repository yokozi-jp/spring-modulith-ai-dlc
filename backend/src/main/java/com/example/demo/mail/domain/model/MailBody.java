package com.example.demo.mail.domain.model;

/**
 * メールのプレーンテキストの本文。
 *
 * @param value 空白だけでない本文
 */
public record MailBody(String value) {

  /** 本文が空白だけでないことを確かめる。 */
  public MailBody {
    if (value.isBlank()) {
      throw new IllegalArgumentException("mailBody must not be blank");
    }
  }
}
