package com.example.demo.shared.infrastructure.persistence;

import com.example.demo.shared.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;
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
 * <p>どちらも {@code set_config(..., true)} で設定し、トランザクションの終わりで元に戻るため、プールの接続に残らない。モジュールが分からないトランザクションは
 * アプリロールのまま、テナントを束縛していないトランザクションはテナントを設定せずに動かす。業務の表の RLS
 * のポリシーは設定のないテナントを読むとエラーにするため、テナントの設定を忘れた読み書きは 0 件にならず失敗する。
 */
public class ModuleRoleTransactionManager extends JdbcTransactionManager {

  /** 業務の表の RLS のポリシーと {@code tenant_public_id} の既定値が読む、トランザクションのテナントの設定。 */
  public static final String TENANT_SETTING = "app.current_tenant";

  /** セッションのロールの設定。 */
  public static final String ROLE_SETTING = "role";

  /** トランザクションマネージャーは {@code Serializable} だが、直列化しない。 */
  private static final long serialVersionUID = 1L;

  /** 接続先のデータソースで作る。 */
  public ModuleRoleTransactionManager(final DataSource dataSource) {
    super(dataSource);
  }

  @Override
  protected void prepareTransactionalConnection(
      final Connection connection, final TransactionDefinition definition) throws SQLException {
    super.prepareTransactionalConnection(connection, definition);
    setLocal(
        connection,
        Stream.of(
                ModuleRoles.roleFor(definition.getName())
                    .map(role -> Map.entry(ROLE_SETTING, role)),
                TenantContext.findCurrent()
                    .map(tenant -> Map.entry(TENANT_SETTING, tenant.toString())))
            .flatMap(Optional::stream)
            .toList());
  }

  /**
   * 設定を、呼び出し中のトランザクションの間だけ、1 つの SQL で変える。名前と値はバインド変数で渡す。
   *
   * <p>Spring のトランザクションの外でロールとテナントを切り替えるテストの補助とシーダーも、本番と同じ SQL を通すためにこれを呼ぶ。
   *
   * @param connection トランザクション中の接続
   * @param settings 設定の名前と値。並びの順に設定する。空なら何もしない
   * @throws SQLException SQL が失敗した場合
   */
  public static void setLocal(
      final Connection connection, final List<Map.Entry<String, String>> settings)
      throws SQLException {
    if (settings.isEmpty()) {
      return;
    }
    final String sql =
        "SELECT "
            + String.join(", ", Collections.nCopies(settings.size(), "set_config(?, ?, true)"));
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < settings.size(); i++) {
        statement.setString(2 * i + 1, settings.get(i).getKey());
        statement.setString(2 * i + 2, settings.get(i).getValue());
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
