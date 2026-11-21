package com.example.demo.shared.infrastructure.persistence;

import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * DB のロールに切り替える機能モジュールと、トランザクションの名前からモジュールのロールを引く規則（ADR-094）。
 *
 * <p>ロールは {@code <module>_module}、スキーマは {@code <module>} である。ロールは環境を作る手順（{@code
 * docker/initdb/03-create-module-roles.sh}、本番は {@code
 * docs/database/connections.md}）で先に作る。モジュールを足したら、{@link #MODULES} とその手順に同じ名前を足す。{@link #MODULES}
 * の足し忘れは、jOOQ のスキーマとの不一致として {@code ModuleRolesTest} が止める。手順の足し忘れは、{@code ModuleRolesTest}
 * とカタログのテストが止める。
 */
public final class ModuleRoles {

  /** ロールとスキーマを持つ機能モジュール。 */
  public static final Set<String> MODULES = Set.of("product", "ordering", "payment", "tenancy");

  /**
   * 業務の表をテナントの RLS で分けるモジュール。トランザクションは、テナントを束縛した呼び出しの中でだけ始める（ADR-107）。
   *
   * <p>{@code tenancy} は、テナントを選ぶ前に引く表を持つため含めない。RLS の外に置くスキーマとの一致は {@code
   * SchemaTenantConventionsTest} が確かめる。
   */
  public static final Set<String> TENANT_SCOPED_MODULES = Set.of("product", "ordering", "payment");

  /** ロール名の接尾辞。 */
  private static final String ROLE_SUFFIX = "_module";

  /** モジュール名を切り出す基準のベースパッケージ（末尾に {@code .} を含む）。 */
  private static final String BASE_PACKAGE =
      PgmCdAspect.basePackageOf(ModuleRoles.class.getPackageName());

  private ModuleRoles() {
    // 静的メソッドだけを持つ。
  }

  /** モジュールのロール名を返す。 */
  public static String roleOf(final String module) {
    return module + ROLE_SUFFIX;
  }

  /**
   * トランザクションの名前から、切り替えるモジュールのロールを引く。
   *
   * <p>名前は {@code @Transactional} のメソッドなら {@code パッケージ.クラス.メソッド}
   * である。ベースパッケージの直下のパッケージがモジュールのとき、そのロールを返す。モジュールに属さない名前（Spring Modulith の内部、名前のない {@code
   * TransactionTemplate}）は空を返し、アプリロールのまま動かす。
   *
   * @param transactionName {@code TransactionDefinition#getName()}。名前がなければ {@code null}
   * @return モジュールのロール。モジュールが分からなければ空
   */
  public static Optional<String> roleFor(final @Nullable String transactionName) {
    return moduleFor(transactionName).map(ModuleRoles::roleOf);
  }

  /**
   * トランザクションの名前のモジュールが、{@link #TENANT_SCOPED_MODULES} に含まれるか。
   *
   * @param transactionName {@code TransactionDefinition#getName()}。名前がなければ {@code null}
   * @return テナントを設定しないと始められないトランザクションなら {@code true}
   */
  public static boolean requiresTenant(final @Nullable String transactionName) {
    return moduleFor(transactionName).filter(TENANT_SCOPED_MODULES::contains).isPresent();
  }

  private static Optional<String> moduleFor(final @Nullable String transactionName) {
    if (transactionName == null || !transactionName.startsWith(BASE_PACKAGE)) {
      return Optional.empty();
    }
    final String relative = transactionName.substring(BASE_PACKAGE.length());
    final int end = relative.indexOf('.');
    final String module = end < 0 ? relative : relative.substring(0, end);
    return MODULES.contains(module) ? Optional.of(module) : Optional.empty();
  }
}
