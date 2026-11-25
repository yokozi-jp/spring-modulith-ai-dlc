package com.example.demo.mail.domain.model;

/**
 * メールの送信の、業務の状態に記録する結果。
 *
 * <p>結果にするのは、同じ要求を送り直しても受け付けられない拒否だけで、ほかの失敗は外部システムの実装が例外を投げる（docs/mail/sending.md の「結果と失敗の扱い」）。
 */
public sealed interface MailOutcome {

  /**
   * 送信のサービスが受け付けたメール。
   *
   * @param messageId 送信のサービスが採番したメールの識別子
   */
  record Sent(MailMessageId messageId) implements MailOutcome {}

  /** 同じ要求を送り直しても受け付けられないため、送らなかったメール。契約に合わない応答もここに入れる。 */
  record Rejected() implements MailOutcome {}

  /** 送信のサービスが受け付けたメール。 */
  static MailOutcome sent(final MailMessageId messageId) {
    return new Sent(messageId);
  }

  /** 同じ要求を送り直しても受け付けられないため、送らなかったメール。 */
  static MailOutcome rejected() {
    return new Rejected();
  }
}
