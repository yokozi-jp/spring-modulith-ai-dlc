package com.example.demo.product.presentation.web;

import com.example.demo.product.application.RegisterProductCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 商品を登録する API の本文。
 *
 * @param productCode 商品コード。テナントの中で一意にする
 * @param productName 商品名
 * @param unitPrice 単価（円）。0 以上で、小数は 2 桁まで
 * @param salesStatus 販売の状態のコード値（ON_SALE か DISCONTINUED）
 */
public record RegisterProductRequest(
    @NotBlank @Size(min = 1, max = 20) @Schema(example = "P-0001") String productCode,
    @NotBlank @Size(min = 1, max = 100) @Schema(example = "ボールペン") String productName,
    @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) @Schema(example = "120.00")
        BigDecimal unitPrice,
    @NotNull @Pattern(regexp = "^(ON_SALE|DISCONTINUED)$") @Schema(example = "ON_SALE")
        String salesStatus) {

  /** Command へ変換する。 */
  public RegisterProductCommand toCommand() {
    return new RegisterProductCommand(productCode, productName, unitPrice, salesStatus);
  }
}
