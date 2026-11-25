package com.example.demo.product.application;

import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.shared.idempotency.IdempotentCommand;
import java.math.BigDecimal;

/**
 * 商品を登録するユースケースの入力。
 *
 * @param productCode 商品コード
 * @param productName 商品名
 * @param unitPrice 単価
 * @param salesStatus 販売の状態のコード値（ON_SALE か DISCONTINUED）
 * @param idempotencyKey クライアントが登録の操作ごとに採番したキー
 */
public record RegisterProductCommand(
    String productCode,
    String productName,
    BigDecimal unitPrice,
    String salesStatus,
    IdempotencyKey idempotencyKey)
    implements IdempotentCommand {}
