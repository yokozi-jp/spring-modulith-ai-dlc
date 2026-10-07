package com.example.demo.ordering.application;

import com.example.demo.ordering.domain.model.Money;
import com.example.demo.ordering.domain.model.OrderedItem;
import com.example.demo.ordering.domain.model.ProductId;
import com.example.demo.ordering.domain.model.ProductOffer;
import com.example.demo.ordering.domain.model.Quantity;
import com.example.demo.product.ProductQueries;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Command の明細を集約に渡す明細と商品の単価にする。下書きと明細の変更の CommandHandler が共有する。 */
final class OrderedItems {

  private OrderedItems() {}

  /** Command の明細の商品 ID と数量を、集約に渡す明細にする。 */
  /* package */ static OrderedItem item(final String productId, final int quantity) {
    return new OrderedItem(new ProductId(UUID.fromString(productId)), new Quantity(quantity));
  }

  /** 明細の商品を読み、商品 ID ごとの単価と販売の状態にする。存在しない商品は含まない。 */
  /* package */ static Map<ProductId, ProductOffer> offers(
      final ProductQueries productQueries, final List<OrderedItem> items) {
    return productQueries
        .findByIds(items.stream().map(item -> item.productId().value().toString()).toList())
        .stream()
        .map(
            summary ->
                new ProductOffer(
                    new ProductId(UUID.fromString(summary.productId())),
                    new Money(summary.unitPrice()),
                    "ON_SALE".equals(summary.salesStatus())))
        .collect(Collectors.toMap(ProductOffer::productId, Function.identity()));
  }
}
