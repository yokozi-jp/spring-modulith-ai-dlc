package com.example.demo.order.domain.model;

/** 注文の明細。注文の中で明細の番号で識別する。 */
// record と同じ形のアクセサ（lineNumber() など）にそろえるため、フィールド名と同名のメソッドを許す。
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class OrderLine {

  /** 注文の中の明細の番号（1 から）。 */
  private final int lineNumber;

  /** 注文する商品の ID。 */
  private final ProductId productId;

  /** 注文する数量。 */
  private final Quantity quantity;

  /** 明細を作ったときに読んだ単価。 */
  private final Money unitPrice;

  /** 明細の番号、商品、数量、単価を受け取る。 */
  public OrderLine(
      final int lineNumber,
      final ProductId productId,
      final Quantity quantity,
      final Money unitPrice) {
    this.lineNumber = lineNumber;
    this.productId = productId;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
  }

  /** 明細の番号を返す。 */
  public int lineNumber() {
    return lineNumber;
  }

  /** 商品の ID を返す。 */
  public ProductId productId() {
    return productId;
  }

  /** 数量を返す。 */
  public Quantity quantity() {
    return quantity;
  }

  /** 単価を返す。 */
  public Money unitPrice() {
    return unitPrice;
  }

  /** 単価と数量の積を返す。 */
  public Money amount() {
    return unitPrice.times(quantity);
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof OrderLine line && lineNumber == line.lineNumber;
  }

  @Override
  public int hashCode() {
    return Integer.hashCode(lineNumber);
  }
}
