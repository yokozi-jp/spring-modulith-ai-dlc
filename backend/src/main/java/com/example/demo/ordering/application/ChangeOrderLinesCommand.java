package com.example.demo.ordering.application;

import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.concurrency.VersionedCommand;
import java.util.List;

/**
 * 下書きの注文の明細を置き換えるユースケースの入力。
 *
 * @param orderId 注文 ID
 * @param lines 置き換えた後の明細
 * @param expectedLockNo 画面が読んだ注文のロック番号
 */
public record ChangeOrderLinesCommand(
    String orderId, List<ChangeOrderLinesCommand.Line> lines, ExpectedLockNo expectedLockNo)
    implements VersionedCommand {

  /** 明細を変更できないリストとして持つ。 */
  public ChangeOrderLinesCommand {
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
