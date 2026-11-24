package com.example.demo.tenancy.domain.model;

/**
 * メールの送信の、業務の状態に記録する結果。
 *
 * <p>一時障害、資格情報と権限の不備、運用者が直せる送信の設定の不備は結果にせず、外部システムの実装が例外を投げる（ADR-125）。
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
}
