package com.example.demo.testkit;

import com.example.demo.shared.tenant.TenantContext;
import com.example.demo.shared.tenant.TenantId;
import java.util.UUID;

/**
 * テストの利用者が選ぶテナント（{@link AppUsers#TENANT_ID}）と、束縛中のテナントを読む補助。
 *
 * <p>本番は、リクエストの入口の filter かリスナーの Aspect がテナントを束縛する。CommandHandler を直接呼ぶテストは、同じ束縛を、テストのメソッド全体を束縛する
 * {@link BindTestTenantExtension} で作る。
 */
public final class AsTenant {

  /** テストの利用者が選ぶテナント。{@link AppUsers#TENANT_ID} と同じ値である。 */
  public static final TenantId TENANT = new TenantId(UUID.fromString(AppUsers.TENANT_ID));

  private AsTenant() {}

  /** 束縛中のテナントの文字列を返す。束縛していなければ {@code unbound} を返す。 */
  public static String currentOrUnbound() {
    try {
      return TenantContext.current().toString();
    } catch (IllegalStateException unbound) {
      return "unbound";
    }
  }
}
