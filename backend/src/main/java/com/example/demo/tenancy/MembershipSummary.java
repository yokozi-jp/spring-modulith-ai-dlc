package com.example.demo.tenancy;

/**
 * 利用者の所属の参照の結果。
 *
 * @param tenantId 所属するテナントの ID
 * @param tenantName 所属するテナントの名前
 * @param role テナントの中の役割のコード値（ADMIN か MEMBER）
 */
public record MembershipSummary(String tenantId, String tenantName, String role) {}
