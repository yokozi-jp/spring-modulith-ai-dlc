package com.example.demo.ordering.presentation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * 注文する商品と数量。
 *
 * @param productId 注文する商品の ID
 * @param quantity 注文する数量
 */
public record OrderLineRequest(
    @NotNull @Schema(example = "0b6c8f6e-2f4a-4c1e-9d3b-7a1e5c2d4f60") UUID productId,
    @Min(1) @Max(9999) @Schema(example = "2") int quantity) {}
