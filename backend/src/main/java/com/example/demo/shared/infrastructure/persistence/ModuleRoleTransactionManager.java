package com.example.demo.shared.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
 * トランザクションを始めた直後に、トランザクションの名前から引いたモジュールのロールへ、そのトランザクションの間だけ切り替える（ADR-094）。
 *
 * <p>{@code set_config('role', ?, true)}
 * はトランザクションの終わりで元のロールへ戻るため、プールの接続にロールが残らない。モジュールが分からないトランザクションは、アプリロールのまま動かす。 ロールの値はバインド変数で渡す。
 */
public class ModuleRoleTransactionManager extends JdbcTransactionManager {

  /** トランザクションマネージャーは {@code Serializable} だが、直列化しない。 */
  private static final long serialVersionUID = 1L;

  /** このトランザクションの間だけ、セッションのロールを切り替える SQL。 */
  private static final String SET_LOCAL_ROLE = "SELECT set_config('role', ?, true)";

  /** 接続先のデータソースで作る。 */
  public ModuleRoleTransactionManager(final DataSource dataSource) {
    super(dataSource);
  }

  @Override
  protected void prepareTransactionalConnection(
      final Connection connection, final TransactionDefinition definition) throws SQLException {
    super.prepareTransactionalConnection(connection, definition);
    final String role = ModuleRoles.roleFor(definition.getName()).orElse(null);
    if (role != null) {
      try (PreparedStatement statement = connection.prepareStatement(SET_LOCAL_ROLE)) {
        statement.setString(1, role);
        statement.execute();
      }
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
