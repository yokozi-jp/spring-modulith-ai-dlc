package com.example.demo.authorization;

/**
 * 認可の表の列。操作する人の種類と、その人のテナントでの役割（ADR-129）。
 *
 * <p>どの種類も、持ち主の注文と所属があるテナント（持ち主のテナント）か、別のテナントの 1 つに所属する。
 */
enum AuthorizationActor {
  /** 持ち主。対象の注文を作った人で、所属の操作では対象の所属の本人である。持ち主のテナントの MEMBER。 */
  OWNER("MEMBER", false),
  /** 持ち主のテナントの、持ち主でない MEMBER。 */
  SAME_TENANT_MEMBER("MEMBER", false),
  /** 持ち主のテナントの、持ち主でない ADMIN。 */
  SAME_TENANT_ADMIN("ADMIN", false),
  /** 別のテナントの MEMBER。 */
  OTHER_TENANT_MEMBER("MEMBER", true),
  /** 別のテナントの ADMIN。所属の管理の API を呼べるが、持ち主のテナントの所属は見えない。 */
  OTHER_TENANT_ADMIN("ADMIN", true);

  /** テナントでの役割。 */
  private final String tenantRole;

  /** 持ち主のテナントでなく、別のテナントに所属するか。 */
  private final boolean inOtherTenant;

  AuthorizationActor(final String role, final boolean otherTenant) {
    this.tenantRole = role;
    this.inOtherTenant = otherTenant;
  }

  /** テナントでの役割（ADMIN か MEMBER）。 */
  /* package */ String role() {
    return tenantRole;
  }

  /** 別のテナントに所属するか。 */
  /* package */ boolean otherTenant() {
    return inOtherTenant;
  }
}
