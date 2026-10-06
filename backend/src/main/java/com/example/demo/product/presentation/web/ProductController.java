package com.example.demo.product.presentation.web;

import com.example.demo.product.ProductQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 商品の HTTP API。 */
@RestController
@RequestMapping("/api/products")
@Tag(name = "product", description = "商品の API")
class ProductController {

  /** 商品の参照。 */
  private final ProductQueries productQueries;

  /** 依存を受け取る。 */
  /* package */ ProductController(final ProductQueries productQueries) {
    this.productQueries = productQueries;
  }

  /**
   * 商品の一覧を返す。
   *
   * <p>一覧は商品コードの昇順で items で包んで返す。
   *
   * @return 商品の一覧
   */
  @Operation(operationId = "listProducts")
  @GetMapping
  /* package */ ProductSummaryListResponse search() {
    // ponytail: ページングしない。確認用の商品は数件である。数百件を超えるなら ADR-013 のカーソルを足す。
    return new ProductSummaryListResponse(
        productQueries.search().stream().map(ProductSummaryResponse::from).toList());
  }
}
