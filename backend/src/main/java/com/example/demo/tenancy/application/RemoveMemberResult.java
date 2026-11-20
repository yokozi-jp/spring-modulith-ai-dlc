package com.example.demo.tenancy.application;

/**
 * テナントから利用者の所属を外すユースケースの結果。
 *
 * @param tenantId 変えたテナントの ID
 * @param userId 所属を外した利用者の ID
 */
public record RemoveMemberResult(String tenantId, String userId) {}
