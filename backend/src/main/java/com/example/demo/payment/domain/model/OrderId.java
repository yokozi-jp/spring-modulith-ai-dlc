package com.example.demo.payment.domain.model;

import java.util.UUID;

/**
 * 決済した注文の ID（{@code order} の注文の {@code public_id}）。
 *
 * @param value 注文 ID の UUID
 */
public record OrderId(UUID value) {}
