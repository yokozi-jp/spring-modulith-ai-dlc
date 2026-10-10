package com.example.demo.product.domain.model;

/** 商品の販売の状態。定数を足すときは、販売中かをコンストラクタの引数で決める。 */
public enum SalesStatus {
  /** 販売中。 */
  ON_SALE(true),
  /** 販売終了。 */
  DISCONTINUED(false);

  /** 注文できる販売中の状態か。 */
  private final boolean onSale;

  SalesStatus(final boolean onSale) {
    this.onSale = onSale;
  }

  /** 注文できる販売中の状態なら true を返す。 */
  public boolean isOnSale() {
    return onSale;
  }
}
