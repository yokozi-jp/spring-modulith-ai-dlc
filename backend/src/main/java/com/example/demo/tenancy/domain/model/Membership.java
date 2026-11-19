package com.example.demo.tenancy.domain.model;

/**
 * 利用者のテナントへの所属。所属するテナントと、そのテナントの中の役割を持つ。
 *
 * @param tenantId 所属するテナントの ID
 * @param tenantName 所属するテナントの名前
 * @param role テナントの中の役割
 */
public record Membership(TenantId tenantId, String tenantName, Role role) {}
