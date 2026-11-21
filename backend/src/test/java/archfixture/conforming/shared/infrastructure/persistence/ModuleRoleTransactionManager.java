package archfixture.conforming.shared.infrastructure.persistence;

import archfixture.conforming.shared.tenant.TenantContext;

/** トランザクションの初めにモジュールのロールへ切り替える入口。フィクスチャなので JDBC を使わず、規則が見る名前だけを持つ。 */
public final class ModuleRoleTransactionManager {

  /** トランザクションの名前から、切り替えるロールを返す。 */
  public String roleFor(final String transactionName) {
    return transactionName;
  }

  /** 束縛のないトランザクションも通すため、テナントを空で受ける。 */
  public String tenantOrEmpty() {
    return TenantContext.findCurrent().orElse("");
  }
}
