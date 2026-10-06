package com.example.demo.order.domain.model;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.failure.NotFoundException;
import java.util.List;
import java.util.Optional;

/** 注文の集約を保存し、取り出す。 */
public interface OrderRepository {

  /** 注文 ID の注文を返す。なければ空を返す。 */
  Optional<Order> findById(OrderId id);

  /** すべての注文を、作成した時刻の新しい順に返す。 */
  List<Order> findAll();

  /** 状態の注文を、作成した時刻の新しい順に返す。 */
  List<Order> findByStatus(OrderStatus status);

  /**
   * 新しい注文を保存する。
   *
   * @throws ConflictException 客先注文番号が既にある場合
   */
  void add(Order order);

  /**
   * 保存済みの注文を、読んだときのロック番号を比べて保存する。
   *
   * @throws NotFoundException 注文の行がない場合
   * @throws ConflictException ロック番号が一致しない場合と、行のロックを {@code lock_timeout} までに取れない場合
   */
  void update(Order order);
}
