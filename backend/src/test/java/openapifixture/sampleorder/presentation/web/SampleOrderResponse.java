package openapifixture.sampleorder.presentation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * サンプル注文の詳細。
 *
 * @param sampleOrderId サンプル注文の ID
 * @param status サンプル注文の状態のコード値
 * @param lines サンプル注文の明細
 */
record SampleOrderResponse(
    @Schema(example = "SO-0001") String sampleOrderId,
    @Schema(example = "PLACED") String status,
    List<OrderLineResponse> lines) {

  /**
   * サンプル注文の明細。
   *
   * @param productCode 商品のコード
   * @param quantity 注文した数量
   */
  public record OrderLineResponse(
      @Schema(example = "P-0001") String productCode, @Schema(example = "2") int quantity) {}
}
