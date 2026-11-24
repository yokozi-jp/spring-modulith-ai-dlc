package com.example.demo.tenancy.domain.model;

/**
 * メールの件名。
 *
 * @param value 空白だけでなく、改行を含まない件名
 */
public record MailSubject(String value) {

  /** 件名が空白だけでなく、1 行であることを確かめる。件名はメールのヘッダーになるため、改行を許さない。 */
  public MailSubject {
    if (value.isBlank()) {
      throw new IllegalArgumentException("mailSubject must not be blank");
    }
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("mailSubject must not contain a line break");
    }
  }
}
