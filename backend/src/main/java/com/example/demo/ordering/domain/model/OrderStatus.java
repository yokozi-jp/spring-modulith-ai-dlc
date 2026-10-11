package com.example.demo.ordering.domain.model;

/** 注文の状態。定数を足すときは、確定した注文かをコンストラクタの引数で決める。 */
public enum OrderStatus {
  /** 下書き。明細を変更でき、確定と取消ができる。 */
  DRAFT(false),
  /** 確定。 */
  CONFIRMED(true),
  /** 取消。 */
  CANCELLED(false);

  /** 確定した注文の状態か。 */
  private final boolean confirmed;

  OrderStatus(final boolean confirmed) {
    this.confirmed = confirmed;
  }

  /** 確定した注文の状態なら true を返す。 */
  public boolean isConfirmed() {
    return confirmed;
  }
}
