package com.example.demo.tenancy.domain.model;

import com.example.demo.shared.failure.FailureReason;

/** テナントの業務規則の違反の理由。定数ごとに 422 の problem type と title が決まる（ADR-125）。 */
public enum TenantFailure implements FailureReason {
  /** 最後の管理者の役割を下げるか、所属を外そうとした。 */
  LAST_ADMIN
}
