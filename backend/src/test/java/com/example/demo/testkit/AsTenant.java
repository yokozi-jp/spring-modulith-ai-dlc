package com.example.demo.testkit;

import com.example.demo.shared.tenant.TenantContext;
import com.example.demo.shared.tenant.TenantId;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * リクエストもリスナーも通らないテストの呼び出しを、{@link AppUsers#TENANT_ID} のテナントを束縛して実行する。
 *
 * <p>本番は、リクエストの入口の filter かリスナーの Aspect が束縛する。CommandHandler を直接呼ぶテストは、同じ束縛をこの補助で作る。
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

  /** テナントを束縛して本体を実行し、結果を返す。 */
  public static <T> T call(final Supplier<T> body) {
    return TenantContext.call(TENANT, body::get);
  }
}
