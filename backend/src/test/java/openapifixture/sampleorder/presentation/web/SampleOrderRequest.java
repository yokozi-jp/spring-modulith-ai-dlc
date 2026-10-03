package openapifixture.sampleorder.presentation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * サンプル注文を受け付ける要求。
 *
 * @param customerId 注文する顧客の ID
 * @param quantity 注文する数量
 * @param giftWrap ギフト包装をするかどうか
 */
record SampleOrderRequest(
    @NotBlank @Schema(example = "C-0001") String customerId,
    @Positive @Schema(example = "2") int quantity,
    boolean giftWrap) {}
