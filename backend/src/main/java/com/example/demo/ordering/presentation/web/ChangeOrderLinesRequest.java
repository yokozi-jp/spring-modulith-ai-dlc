package com.example.demo.ordering.presentation.web;

import com.example.demo.ordering.application.ChangeOrderLinesCommand;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 下書きの注文の明細を置き換える API の本文。
 *
 * @param lines 置き換えた後の明細
 * @param lockNo 画面が読んだ注文のロック番号
 */
public record ChangeOrderLinesRequest(
    @NotEmpty @Size(min = 1, max = 100) List<@Valid OrderLineRequest> lines,
    @NotNull @Min(1) @Schema(example = "1") long lockNo) {

  /** 明細を変更できないリストとして持つ。 */
  public ChangeOrderLinesRequest {
    lines = List.copyOf(lines);
  }

  /** パス変数の注文 ID と合わせて Command へ変換する。 */
  public ChangeOrderLinesCommand toCommand(final UUID orderId) {
    return new ChangeOrderLinesCommand(
        orderId.toString(),
        lines.stream()
            .map(
                line ->
                    new ChangeOrderLinesCommand.Line(line.productId().toString(), line.quantity()))
            .toList(),
        new ExpectedLockNo(lockNo));
  }
}
