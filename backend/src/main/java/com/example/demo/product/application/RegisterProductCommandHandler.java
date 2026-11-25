package com.example.demo.product.application;

import com.example.demo.product.domain.model.Product;
import com.example.demo.product.domain.model.ProductRepository;
import com.example.demo.product.domain.model.SalesStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 商品を登録する。 */
@Service
public class RegisterProductCommandHandler {

  /** 商品を保存する Repository。 */
  private final ProductRepository productRepository;

  /** 依存を受け取る。 */
  public RegisterProductCommandHandler(final ProductRepository productRepository) {
    this.productRepository = productRepository;
  }

  /** 商品を作り、保存する。 */
  @Transactional
  public RegisterProductResult handle(final RegisterProductCommand command) {
    final Product product =
        Product.register(
            command.productCode(),
            command.productName(),
            command.unitPrice(),
            SalesStatus.valueOf(command.salesStatus()));
    productRepository.add(product);
    return new RegisterProductResult(product.id().value().toString());
  }
}
