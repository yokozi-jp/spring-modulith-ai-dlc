package com.example.demo.ordering.domain.model;

import java.util.UUID;

/**
 * 注文 ID（{@code public_id}）。
 *
 * @param value 注文 ID の UUID
 */
public record OrderId(UUID value) {}
