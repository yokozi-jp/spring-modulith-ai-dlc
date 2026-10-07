package com.example.demo.payment.domain.model;

import java.util.UUID;

/**
 * 決済記録の ID（{@code public_id}）。
 *
 * @param value 決済記録の ID の UUID
 */
public record PaymentId(UUID value) {}
