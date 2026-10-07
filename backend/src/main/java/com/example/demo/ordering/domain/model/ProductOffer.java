package com.example.demo.ordering.domain.model;

/**
 * 明細を作るときに読んだ商品の単価と販売の状態。
 *
 * @param productId 商品の ID
 * @param unitPrice 単価
 * @param onSale 販売中なら true
 */
public record ProductOffer(ProductId productId, Money unitPrice, boolean onSale) {}
