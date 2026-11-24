package com.example.demo.shared.persistence;

import com.example.demo.shared.tenancy.TenantContext;
import com.example.demo.shared.tenancy.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * トランザクションを始めた直後に、トランザクションの名前から引いたモジュールのロールと、{@link TenantContext}
 * に束縛したテナントを、そのトランザクションの間だけ設定する（ADR-094、ADR-107）。
 *
 * <p>どちらも {@code set_config(..., true)} で設定し、トランザクションの終わりで元に戻るため、プールの接続に残らない。
 *
 * <p>テナントのない業務のトランザクションを SQL の前に止めるのは、RLS のポリシーのエラーに頼れないためである。汎用の実行計画では、RLS
 * の式より先に別の条件が全行を落とすと、エラーにならず 0 件を返すことがある。
 */
public class ModuleRoleTransactionManager extends JdbcTransactionManager {

  /** 業務の表の RLS のポリシーと {@code tenant_public_id} の既定値が読む、トランザクションのテナントの設定。 */
  public static final String TENANT_SETTING = "app.current_tenant";

  /** トランザクションのロールの設定。 */
  private static final String ROLE_SETTING = "role";

  /** トランザクションマネージャーは {@code Serializable} だが、直列化しない。 */
  private static final long serialVersionUID = 1L;

  /** 接続先のデータソースで作る。 */
  public ModuleRoleTransactionManager(final DataSource dataSource) {
    super(dataSource);
  }

  @Override
  protected void prepareTransactionalConnection(
      final Connection connection, final TransactionDefinition definition) throws SQLException {
    final @Nullable String name = definition.getName();
    final Optional<TenantId> tenant = TenantContext.findCurrent();
    if (tenant.isEmpty() && ModuleRoles.requiresTenant(name)) {
      throw new IllegalStateException(
          "tenant is not bound for the transaction "
              + name
              + ": start it from a request with a selected tenant or an @ApplicationModuleListener");
    }
    super.prepareTransactionalConnection(connection, definition);
    setLocal(
        connection,
        ModuleRoles.roleFor(name).orElse(null),
        tenant.map(TenantId::toString).orElse(null));
  }

  /**
   * ロールとテナントを、呼び出し中のトランザクションの間だけ変える。
   *
   * <p>本番のコードでは、このクラスだけが呼ぶ。Spring のトランザクションの外でロールとテナントを切り替えるテストの補助とシーダーも、本番と同じ SQL を通すためにこれを呼ぶ。
   *
   * @param connection トランザクション中の接続
   * @param role 設定するロール。{@code null} ならロールを変えない
   * @param tenant 設定するテナント。{@code null} ならテナントを変えない
   * @throws SQLException SQL が失敗した場合
   */
  public static void setLocal(
      final Connection connection, final @Nullable String role, final @Nullable String tenant)
      throws SQLException {
    final List<Map.Entry<String, String>> settings =
        Stream.of(
                Optional.ofNullable(role).map(value -> Map.entry(ROLE_SETTING, value)),
                Optional.ofNullable(tenant).map(value -> Map.entry(TENANT_SETTING, value)))
            .flatMap(Optional::stream)
            .toList();
    if (settings.isEmpty()) {
      return;
    }
    // 設定の名前は定数だけで、値はバインド変数で渡す。
    final String sql =
        settings.stream()
            .map(setting -> "set_config('" + setting.getKey() + "', ?, true)")
            .collect(Collectors.joining(", ", "SELECT ", ""));
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < settings.size(); i++) {
        statement.setString(i + 1, settings.get(i).getValue());
      }
      statement.execute();
    }
  }

  /** Spring Boot の自動構成の代わりに、この子クラスをトランザクションマネージャーにする。 */
  @Configuration(proxyBeanMethods = false)
  public static class Config {

    /**
     * Spring Boot の自動構成と同じく、{@code TransactionManagerCustomizers} を適用する。
     *
     * <p>インフラのロールにして、Spring Modulith の観測がアプリケーションのクラスとして CGLIB で包まないようにする。包むと、{@code final} の
     * {@code getTransaction} が、フィールドの空の包みの側で動いて失敗する。
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public ModuleRoleTransactionManager transactionManager(
        final DataSource dataSource,
        final ObjectProvider<TransactionManagerCustomizers> customizers) {
      final ModuleRoleTransactionManager manager = new ModuleRoleTransactionManager(dataSource);
      customizers.ifAvailable(applicable -> applicable.customize(manager));
      return manager;
    }
  }
}
