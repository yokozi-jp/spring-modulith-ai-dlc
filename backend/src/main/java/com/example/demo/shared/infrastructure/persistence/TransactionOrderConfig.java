package com.example.demo.shared.infrastructure.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * {@code @Transactional} の advisor の順序を明示する（ADR-051）。
 *
 * <p>Spring は、別々の advisor が同じメソッドに掛かるときの順序を、指定しなければ決めない。Aspect は {@link #ORDER} との前後を
 * {@code @Order} で決める。値と {@code proxyTargetClass} は Spring Boot の既定と同じにし、振る舞いを変えない。
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true, order = TransactionOrderConfig.ORDER)
class TransactionOrderConfig {

  /** トランザクションの advisor の順序。値が小さい advisor ほど外側で動く。 */
  /* package */ static final int ORDER = Ordered.LOWEST_PRECEDENCE;
}
