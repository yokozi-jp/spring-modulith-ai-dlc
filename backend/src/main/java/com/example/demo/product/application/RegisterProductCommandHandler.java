package com.example.demo.product.application;

import com.example.demo.product.domain.model.Product;
import com.example.demo.product.domain.model.ProductRepository;
import com.example.demo.product.domain.model.SalesStatus;
import com.example.demo.shared.idempotency.Idempotency;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 商品を登録する。 */
@Service
public class RegisterProductCommandHandler {

  /** 商品を保存する Repository。 */
  private final ProductRepository productRepository;

  /** 同じ冪等性キーの再送に、保存した Result を返す。 */
  private final Idempotency idempotency;

  /** 依存を受け取る。 */
  public RegisterProductCommandHandler(
      final ProductRepository productRepository, final Idempotency idempotency) {
    this.productRepository = productRepository;
    this.idempotency = idempotency;
  }

  /** 商品を作り、保存する。同じ冪等性キーの再送には、保存した Result を返す。 */
  @Transactional
  public RegisterProductResult handle(final RegisterProductCommand command) {
    return idempotency.execute(command, RegisterProductResult.class, () -> register(command));
  }

  private RegisterProductResult register(final RegisterProductCommand command) {
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
