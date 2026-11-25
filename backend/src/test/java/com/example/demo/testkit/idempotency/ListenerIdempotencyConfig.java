package com.example.demo.testkit.idempotency;

import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.transaction.support.TransactionOperations;

/** 全件のテストと自己検査が {@code @Import} する、検査と門と呼び出しの記録の構成。 */
@TestConfiguration(proxyBeanMethods = false)
public class ListenerIdempotencyConfig {

  /** 外部システムの呼び出しの記録。ほかの Bean の後処理に使うため static にする。 */
  @Bean
  /* package */ static ExternalCallRecorder externalCallRecorder() {
    return new ExternalCallRecorder();
  }

  /** コミットの直前の門。 */
  @Bean
  /* package */ CommitGate commitGate(final DSLContext dsl) {
    return new CommitGate(dsl);
  }

  /** 検査。 */
  @Bean
  /* package */ ListenerIdempotencyChecker listenerIdempotencyChecker(
      final ConfigurableListableBeanFactory beanFactory,
      final ObjectProvider<ListenerExample> examples,
      final DSLContext dsl,
      final TransactionOperations transactions,
      final ApplicationEventPublisher publisher,
      final EventSerializer serializer,
      final Environment environment,
      final CommitGate gate,
      final ExternalCallRecorder calls) {
    final PublicationRegistry registry =
        new PublicationRegistry(dsl, transactions, publisher, serializer);
    return new ListenerIdempotencyChecker(
        beanFactory,
        examples.orderedStream().toList(),
        new SequentialCheck(dsl, registry, calls, environment),
        new ConcurrentCheck(dsl, registry, gate, calls, environment));
  }
}
