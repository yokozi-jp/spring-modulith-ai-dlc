package com.example.demo.tenancy.presentation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * テナントを選ぶ API の本文。
 *
 * @param tenantId 選ぶテナントの ID
 */
public record SelectTenantRequest(
    @NotNull @Schema(example = "6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b") UUID tenantId) {}
