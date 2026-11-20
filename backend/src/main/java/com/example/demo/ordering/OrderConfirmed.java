package com.example.demo.ordering;

import com.example.demo.shared.tenant.TenantId;
import com.example.demo.shared.tenant.TenantScoped;
import java.time.Instant;

/**
 * 注文を確定したことを、他モジュールと自モジュールの後続の処理へ通知するイベント。
 *
 * @param orderId 確定した注文の ID
 * @param confirmedAt 確定した時刻
 * @param tenantId 注文を確定したテナント
 */
public record OrderConfirmed(String orderId, Instant confirmedAt, TenantId tenantId)
    implements TenantScoped {}
