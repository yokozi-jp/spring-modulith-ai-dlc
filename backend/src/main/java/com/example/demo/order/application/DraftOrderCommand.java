package com.example.demo.order.application;

import java.util.List;

/**
 * 下書きの注文を作るユースケースの入力。
 *
 * @param customerOrderCode 客先注文番号
 * @param lines 注文の明細
 */
public record DraftOrderCommand(String customerOrderCode, List<DraftOrderCommand.Line> lines) {

  /** 明細を変更できないリストとして持つ。 */
  public DraftOrderCommand {
    lines = List.copyOf(lines);
  }

  /**
   * 注文する商品と数量。
   *
   * @param productId 商品 ID
   * @param quantity 数量
   */
  public record Line(String productId, int quantity) {}
}
