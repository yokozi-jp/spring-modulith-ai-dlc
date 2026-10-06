package com.example.demo.order.presentation.web;

import com.example.demo.order.application.CancelOrderCommand;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.util.UUID;

/**
 * 下書きの注文を取り消す API の本文。
 *
 * @param lockNo 画面が読んだ注文のロック番号
 */
public record CancelOrderRequest(@Min(1) @Schema(example = "1") long lockNo) {

  /** パス変数の注文 ID と合わせて Command へ変換する。 */
  public CancelOrderCommand toCommand(final UUID orderId) {
    return new CancelOrderCommand(orderId.toString(), new ExpectedLockNo(lockNo));
  }
}
