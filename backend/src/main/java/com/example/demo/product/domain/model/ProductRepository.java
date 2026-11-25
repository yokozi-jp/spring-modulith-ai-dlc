package com.example.demo.product.domain.model;

import com.example.demo.shared.concurrency.ConflictException;
import java.util.Collection;
import java.util.List;

/** 商品の集約を保存し、取り出す。 */
public interface ProductRepository {

  /** すべての商品を商品コードの昇順で返す。 */
  List<Product> findAll();

  /** 指定した ID の商品を返す。存在しない ID の商品は含めない。 */
  List<Product> findByIds(Collection<ProductId> ids);

  /**
   * 新しい商品を保存する。
   *
   * @throws ConflictException 商品コードがテナントの中に既にある場合
   */
  void add(Product product);
}
