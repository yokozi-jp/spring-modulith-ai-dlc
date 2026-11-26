package com.example.demo.product.presentation.web;

import com.example.demo.product.ProductQueries;
import com.example.demo.product.application.RegisterProductCommandHandler;
import com.example.demo.product.application.RegisterProductResult;
import com.example.demo.shared.failure.NotFoundException;
import com.example.demo.shared.idempotency.IdempotencyKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** 商品の HTTP API。 */
@RestController
@RequestMapping(path = "/api/v{version}/products", version = "1")
@Tag(name = "product", description = "商品の API")
class ProductController {

  /** 商品を登録する CommandHandler。 */
  private final RegisterProductCommandHandler registerProduct;

  /** 商品の参照。 */
  private final ProductQueries productQueries;

  /** 依存を受け取る。 */
  /* package */ ProductController(
      final RegisterProductCommandHandler registerProduct, final ProductQueries productQueries) {
    this.registerProduct = registerProduct;
    this.productQueries = productQueries;
  }

  /**
   * 商品を登録する。
   *
   * <p>登録した商品の URI を Location に入れて返す。商品コードが既にあれば 409 を返す。
   *
   * @param idempotencyKey 登録の操作ごとにクライアントが採番したキー
   * @param request 登録する商品の内容
   * @return 本文のない 201 の応答
   */
  @Operation(operationId = "registerProduct")
  @ApiResponse(
      responseCode = "201",
      headers =
          @Header(
              name = "Location",
              description = "登録した商品の URI",
              schema = @Schema(type = "string", format = "uri")))
  @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictProblem")
  @PostMapping
  /* package */ ResponseEntity<Void> register(
      final IdempotencyKey idempotencyKey,
      @Valid @RequestBody final RegisterProductRequest request) {
    final RegisterProductResult result = registerProduct.handle(request.toCommand(idempotencyKey));
    final URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{productId}")
            .buildAndExpand(result.productId())
            .toUri();
    return ResponseEntity.created(location).build();
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
    // ponytail: ページングしない。参照業務機能の商品は数件である。数百件を超えるなら ADR-013 のカーソルを足す。
    return new ProductSummaryListResponse(
        productQueries.search().stream().map(ProductSummaryResponse::from).toList());
  }

  /**
   * 商品を返す。
   *
   * <p>商品がなければ 404 を返す。
   *
   * @param productId 商品の ID
   * @return 商品
   */
  @Operation(operationId = "findProductById")
  @ApiResponse(responseCode = "200")
  @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundProblem")
  @GetMapping("/{productId}")
  /* package */ ProductSummaryResponse details(@PathVariable final UUID productId) {
    return productQueries
        .findById(productId.toString())
        .map(ProductSummaryResponse::from)
        .orElseThrow(() -> new NotFoundException("product not found: productId=" + productId));
  }
}
