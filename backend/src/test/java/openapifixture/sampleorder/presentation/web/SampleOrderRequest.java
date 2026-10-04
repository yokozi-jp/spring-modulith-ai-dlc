package openapifixture.sampleorder.presentation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * サンプル注文を受け付ける要求。
 *
 * @param customerId 注文する顧客の ID
 * @param quantity 注文する数量
 * @param giftWrap ギフト包装をするかどうか
 * @param lines サンプル注文の明細
 */
record SampleOrderRequest(
    @NotBlank @Schema(example = "C-0001") String customerId,
    @Positive @Schema(example = "2") int quantity,
    boolean giftWrap,
    List<@Valid PlaceOrderLineRequest> lines) {

  /**
   * サンプル注文で注文する商品と数量。
   *
   * @param productCode 注文する商品のコード
   * @param quantity 注文する数量
   */
  public record PlaceOrderLineRequest(
      @NotBlank @Schema(example = "P-0001") String productCode,
      @Positive @Schema(example = "2") int quantity) {}
}
