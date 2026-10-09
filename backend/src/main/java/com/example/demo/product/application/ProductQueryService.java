package com.example.demo.product.application;

import com.example.demo.product.ProductQueries;
import com.example.demo.product.ProductSummary;
import com.example.demo.product.domain.model.Product;
import com.example.demo.product.domain.model.ProductId;
import com.example.demo.product.domain.model.ProductRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 商品の参照を、Repository で読んだ集約から作る。 */
@Service
class ProductQueryService implements ProductQueries {

  /** 商品を取り出す Repository。 */
  private final ProductRepository productRepository;

  /** 商品を取り出す Repository を受け取る。 */
  /* package */ ProductQueryService(final ProductRepository productRepository) {
    this.productRepository = productRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProductSummary> search() {
    return productRepository.findAll().stream().map(ProductQueryService::toSummary).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProductSummary> findByIds(final List<String> productIds) {
    return productRepository
        .findByIds(productIds.stream().map(id -> new ProductId(UUID.fromString(id))).toList())
        .stream()
        .map(ProductQueryService::toSummary)
        .toList();
  }

  private static ProductSummary toSummary(final Product product) {
    return new ProductSummary(
        product.id().value().toString(),
        product.productCode(),
        product.productName(),
        product.unitPrice(),
        product.salesStatus().name());
  }
}
