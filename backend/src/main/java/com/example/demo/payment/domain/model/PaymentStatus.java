package com.example.demo.payment.domain.model;

/** 決済代行への請求の結果（ADR-072）。再投入で回復できる失敗は、決済記録を作らずイベント出版の FAILED に残すため、ここに含めない。 */
public enum PaymentStatus {
  /** 決済代行が請求を受け付けた。 */
  PAID,
  /** 決済代行がカードの拒否や限度額の超過で請求を拒否した。 */
  DECLINED,
  /** 契約の不備（401、403、429 以外の 4xx、または契約に合わない応答）で請求が失敗した。同じ要求を再投入しても回復しない。 */
  FAILED
}
