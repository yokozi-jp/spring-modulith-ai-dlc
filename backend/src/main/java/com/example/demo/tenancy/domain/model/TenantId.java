package com.example.demo.tenancy.domain.model;

import java.util.UUID;

/**
 * テナント ID（{@code public_id}）。テナントの外に出す値で、業務の表、セッション、API でテナントを指す。
 *
 * @param value テナント ID の UUID
 */
public record TenantId(UUID value) {}
