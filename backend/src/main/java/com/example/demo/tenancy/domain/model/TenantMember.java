package com.example.demo.tenancy.domain.model;

/**
 * テナントの所属している利用者と、その役割。
 *
 * @param userId 所属している利用者の ID
 * @param role テナントの中の役割
 */
public record TenantMember(UserId userId, Role role) {}
