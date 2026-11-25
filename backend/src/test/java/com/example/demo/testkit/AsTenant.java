package com.example.demo.testkit;

import com.example.demo.shared.tenancy.TenantContext;
import com.example.demo.shared.tenancy.TenantId;
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

  /** {@link #TENANT} でないテナント。2 つのテナントの行を比べるテストが使う。 */
  public static final TenantId OTHER_TENANT =
      new TenantId(UUID.fromString("9c1f6a2e-3b4d-4e5f-8a70-1b2c3d4e5f60"));

  private AsTenant() {}

  /** 束縛中のテナントの文字列を返す。束縛していなければ {@code unbound} を返す。 */
  public static String currentOrUnbound() {
    return TenantContext.findCurrent().map(TenantId::toString).orElse("unbound");
  }
}
