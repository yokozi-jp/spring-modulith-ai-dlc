package com.example.demo.mail.domain.model;

/** 利用者へメールを送る外部システム。 */
// 外部システムの interface であり、ラムダで実装する関数型 interface ではない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface MailSender {

  /**
   * 1 人の宛先へ、プレーンテキストのメールを 1 通送り、業務の状態に記録する結果を返す。
   *
   * <p>送信の API に冪等キーがないため、同じ引数で 2 回呼ぶと 2 通届く。再投入で 2 通目を送らない扱いは、呼び出し元が決める。
   *
   * <p>同じ要求を送り直しても受け付けられない拒否は結果で返す。一時障害、資格情報と権限の不備、運用者が直せる送信の設定の不備は例外を投げ、再投入でやり直せるようにする（ADR-125）。
   */
  MailOutcome send(MailRecipient to, MailSubject subject, MailBody body);
}
