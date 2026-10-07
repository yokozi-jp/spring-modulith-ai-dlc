package com.example.demo.shared.infrastructure.persistence;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * jOOQ が発行する SQL ごとに Micrometer の observation（span と timer）を作る設定（ADR-070）。
 *
 * <p>observation の名前は {@code jooq.query}、span の名前は SQL の種類（{@code READ}、{@code WRITE} など）にする。 SQL
 * の文、バインドの値、例外のメッセージは、どの key value にも入れない。 バックエンドの trace は Collector で加工しないので、発生源で渡さない
 * （docs/observability/conventions.md の「発生源で渡さない値」）。
 *
 * <p>親は registry の現在の observation（HTTP の server の observation など）から決まる。 scope は開かないので、SQL の実行中も現在の
 * span は変わらない。
 */
@Configuration(proxyBeanMethods = false)
public class JooqObservationConfig {

  /** Spring Boot の jOOQ の自動構成が、この provider の listener を jOOQ の設定に入れる。 */
  @Bean
  public DefaultExecuteListenerProvider jooqObservationListenerProvider(
      final ObservationRegistry registry) {
    return new DefaultExecuteListenerProvider(new ObservationListener(registry));
  }

  /** SQL の開始で observation を start し、終了で stop する。 */
  private static final class ObservationListener implements ExecuteListener {

    private static final long serialVersionUID = 1L;

    /** {@link ExecuteContext#data} に observation を置くキー。 */
    private static final String KEY = ObservationListener.class.getName();

    /** observation を作る registry。 */
    private final transient ObservationRegistry registry;

    /* package */ ObservationListener(final ObservationRegistry registry) {
      this.registry = registry;
    }

    @Override
    public void start(final ExecuteContext ctx) {
      final Observation observation =
          Observation.createNotStarted("jooq.query", registry)
              .contextualName(ctx.type().name())
              .lowCardinalityKeyValue("db.system.name", "postgresql")
              .start();
      ctx.data(KEY, observation);
    }

    @Override
    public void exception(final ExecuteContext ctx) {
      // observation.error は例外のメッセージを span の event に残す。
      // PostgreSQL のメッセージは制約違反の値を含みうるので、クラス名だけを付ける。
      // そのため失敗した SQL の span の status は UNSET のままになる（ADR-070）。
      final RuntimeException exception = ctx.exception();
      if (ctx.data(KEY) instanceof Observation observation && exception != null) {
        observation.lowCardinalityKeyValue("error.type", exception.getClass().getName());
      }
    }

    @Override
    public void end(final ExecuteContext ctx) {
      if (ctx.data(KEY) instanceof Observation observation) {
        observation.stop();
        ctx.data(KEY, null);
      }
    }
  }
}
