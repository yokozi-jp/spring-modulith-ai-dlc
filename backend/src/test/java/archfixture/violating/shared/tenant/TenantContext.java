package archfixture.violating.shared.tenant;

import java.util.Optional;
import java.util.function.Supplier;

/** 違反フィクスチャが使う、呼び出し中のテナントの入口。これ自体は規約どおり。 */
public final class TenantContext {

  private TenantContext() {}

  /** テナントを束縛して処理を呼ぶ。フィクスチャは束縛を持たず、処理をそのまま呼ぶ。 */
  public static <R> R call(final String tenantId, final Supplier<R> operation) {
    return operation.get();
  }

  /** 呼び出し中のテナント。 */
  public static String current() {
    return "tenant-1";
  }

  /** 呼び出し中のテナント。フィクスチャは束縛を持たず、空を返す。 */
  public static Optional<String> findCurrent() {
    return Optional.empty();
  }
}
