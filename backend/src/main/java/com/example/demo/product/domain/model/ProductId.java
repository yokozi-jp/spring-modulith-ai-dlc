package com.example.demo.product.domain.model;

import java.util.UUID;

/**
 * 商品 ID（{@code public_id}）。
 *
 * @param value 商品 ID の UUID
 */
public record ProductId(UUID value) {}
