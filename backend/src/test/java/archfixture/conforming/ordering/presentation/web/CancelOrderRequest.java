package archfixture.conforming.ordering.presentation.web;

import archfixture.conforming.ordering.application.CancelOrderCommand;
import archfixture.conforming.shared.concurrency.ExpectedLockNo;
import archfixture.conforming.shared.idempotency.IdempotencyKey;
import jakarta.validation.constraints.Min;

/** 注文を取り消す API の本文。画面が表示した版を数値で受け取る。 */
public record CancelOrderRequest(@Min(1) long lockNo) {

  /** 版を ExpectedLockNo にして、ヘッダーのキーと合わせて Command へ変換する。 */
  public CancelOrderCommand toCommand(final String orderId, final IdempotencyKey idempotencyKey) {
    return new CancelOrderCommand(orderId, new ExpectedLockNo(lockNo), idempotencyKey);
  }
}
