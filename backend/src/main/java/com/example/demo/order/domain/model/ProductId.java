package com.example.demo.order.domain.model;

import java.util.UUID;

/**
 * 明細が指す商品の ID（product モジュールの商品 ID）。
 *
 * @param value 商品 ID の UUID
 */
public record ProductId(UUID value) {}
