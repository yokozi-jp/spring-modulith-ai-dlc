package archfixture.conforming.shared.tenant;

/** 呼び出し中のテナントを返す入口。フィクスチャは束縛を持たず、固定のテナントを返す。 */
public final class TenantContext {

  private TenantContext() {}

  /** 呼び出し中のテナント。 */
  public static String current() {
    return "tenant-1";
  }
}
