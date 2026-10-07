package com.example.demo.ordering.presentation.web;

import com.example.demo.ordering.application.DraftOrderCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 下書きの注文を作る API の本文。
 *
 * @param customerOrderCode 客先注文番号。注文ごとに一意にする
 * @param lines 注文の明細
 */
public record DraftOrderRequest(
    @NotBlank @Size(max = 30) @Schema(example = "C-2026-0001") String customerOrderCode,
    @NotEmpty @Size(max = 100) List<@Valid OrderLineRequest> lines) {

  /** 明細を変更できないリストとして持つ。 */
  public DraftOrderRequest {
    lines = List.copyOf(lines);
  }

  /** Command へ変換する。 */
  public DraftOrderCommand toCommand() {
    return new DraftOrderCommand(
        customerOrderCode,
        lines.stream()
            .map(line -> new DraftOrderCommand.Line(line.productId().toString(), line.quantity()))
            .toList());
  }
}
