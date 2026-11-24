package archfixture.conforming.shared.tenancy;

/** テナントを運ぶイベントの型。規則は型の名前だけを見るため、テナントの ID は文字列にする。 */
@FunctionalInterface
public interface TenantScoped {

  /** イベントが発生したテナント。 */
  String tenantId();
}
