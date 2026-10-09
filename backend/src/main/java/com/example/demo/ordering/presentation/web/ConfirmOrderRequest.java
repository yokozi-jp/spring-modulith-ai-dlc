package com.example.demo.ordering.presentation.web;

import com.example.demo.ordering.application.ConfirmOrderCommand;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * 下書きの注文を確定する API の本文。
 *
 * @param lockNo 画面が読んだ注文のロック番号
 */
public record ConfirmOrderRequest(@NotNull @Min(1) @Schema(example = "1") long lockNo) {

  /** パス変数の注文 ID と合わせて Command へ変換する。 */
  public ConfirmOrderCommand toCommand(final UUID orderId) {
    return new ConfirmOrderCommand(orderId.toString(), new ExpectedLockNo(lockNo));
  }
}
