package archfixture.conforming.shared.infrastructure.persistence;

/** トランザクションの初めにモジュールのロールへ切り替える入口。フィクスチャなので JDBC を使わず、規則が見る名前だけを持つ。 */
public final class ModuleRoleTransactionManager {

  /** トランザクションの名前から、切り替えるロールを返す。 */
  public String roleFor(final String transactionName) {
    return transactionName;
  }
}
