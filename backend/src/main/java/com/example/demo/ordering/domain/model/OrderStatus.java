package com.example.demo.ordering.domain.model;

/** 注文の状態。 */
public enum OrderStatus {
  /** 下書き。明細を変更でき、確定と取消ができる。 */
  DRAFT,
  /** 確定。 */
  CONFIRMED,
  /** 取消。 */
  CANCELLED
}
