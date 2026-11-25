package com.example.demo.ordering.application;

import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.shared.idempotency.IdempotentCommand;
import java.util.List;

/**
 * 下書きの注文を作るユースケースの入力。
 *
 * @param customerOrderCode 客先注文番号
 * @param lines 注文の明細
 * @param idempotencyKey クライアントが作成の操作ごとに採番したキー
 */
public record DraftOrderCommand(
    String customerOrderCode, List<DraftOrderCommand.Line> lines, IdempotencyKey idempotencyKey)
    implements IdempotentCommand {

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
