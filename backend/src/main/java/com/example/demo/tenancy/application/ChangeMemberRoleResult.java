package com.example.demo.tenancy.application;

/**
 * テナントに所属している利用者の役割を変えるユースケースの結果。
 *
 * @param tenantId 変えたテナントの ID
 * @param userId 役割を変えた利用者の ID
 */
public record ChangeMemberRoleResult(String tenantId, String userId) {}
