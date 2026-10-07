package com.example.demo.ordering.domain.model;

/**
 * 画面が指定した明細。
 *
 * @param productId 注文する商品の ID
 * @param quantity 注文する数量
 */
public record OrderedItem(ProductId productId, Quantity quantity) {}
