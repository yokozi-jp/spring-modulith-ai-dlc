package com.example.demo.product;

import java.util.List;
import java.util.Optional;

/**
 * 商品の参照を、自モジュールの Controller と他モジュールへ公開する。
 *
 * <p>{@link ProductSummary#salesStatus()} は {@code ON_SALE}（販売中）か {@code DISCONTINUED}（販売終了）である。
 * 他モジュールは販売中かを {@link ProductSummary#onSale()} で判断する。
 */
public interface ProductQueries {

  /** すべての商品を商品コードの昇順で返す。 */
  List<ProductSummary> search();

  /** 指定した商品 ID の商品を返す。商品がなければ空を返す。 */
  Optional<ProductSummary> findById(String productId);

  /** 指定した商品 ID の商品を返す。存在しない ID の商品は結果に含めない。 */
  List<ProductSummary> findByIds(List<String> productIds);
}
