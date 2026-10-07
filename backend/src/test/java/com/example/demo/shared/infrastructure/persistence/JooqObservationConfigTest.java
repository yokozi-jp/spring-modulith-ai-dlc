package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.testkit.DatabaseTest;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** jOOQ の SQL ごとに、SQL の文と値を持たない observation が作られることを検証する。 */
@DatabaseTest
@Import({JooqObservationConfig.class, JooqObservationConfigTest.Registry.class})
class JooqObservationConfigTest {

  /** 検証対象の SQL を発行する。 */
  @Autowired private DSLContext dsl;

  /** 親の observation を作る。 */
  @Autowired private ObservationRegistry registry;

  /** stop された observation を記録する。 */
  @Autowired private RecordingHandler handler;

  @BeforeEach
  void clear() {
    handler.stopped.clear();
    handler.errors.clear();
  }

  @Test
  @DisplayName("SQL ごとに、外側の observation を親とする jooq.query の observation を作る")
  void recordsQueryAsChildObservation() {
    final Observation parent = Observation.createNotStarted("parent", registry);

    parent.observe(() -> dsl.selectOne().fetch());

    final List<Observation.Context> queries = handler.queries();
    assertThat(queries).as("jooq.query の observation が 1 つであること").hasSize(1);
    final Observation.Context query = queries.getFirst();
    assertThat(query.getContextualName()).as("span の名前は SQL の種類であること").isEqualTo("READ");
    assertThat(query.getLowCardinalityKeyValue("db.system.name"))
        .as("DB の種類を持つこと")
        .isEqualTo(KeyValue.of("db.system.name", "postgresql"));
    assertThat(query.getParentObservation()).as("親が外側の observation であること").isSameAs(parent);
    assertThat(query.getAllKeyValues().stream().map(KeyValue::getValue))
        .as("SQL の文を key value に入れないこと")
        .noneMatch(value -> value.toLowerCase(Locale.ROOT).contains("select"));
  }

  @Test
  @DisplayName("失敗した SQL は、例外のメッセージを残さず error.type だけを付けて stop する")
  void recordsFailureWithoutMessage() {
    assertThatThrownBy(() -> dsl.select(DSL.inline(1).div(0)).fetch())
        .as("0 で割る SQL が失敗すること")
        .isInstanceOf(RuntimeException.class);

    final List<Observation.Context> queries = handler.queries();
    assertThat(queries).as("失敗した SQL の observation も stop されること").hasSize(1);
    final KeyValue errorType = queries.getFirst().getLowCardinalityKeyValue("error.type");
    assertThat(errorType).as("error.type を持つこと").isNotNull();
    assertThat(errorType.getValue())
        .as("error.type は例外のクラス名で、メッセージを含まないこと")
        .isNotBlank()
        .doesNotContainIgnoringCase("division");
    assertThat(queries.getFirst().getError()).as("例外を observation に記録しないこと").isNull();
    assertThat(handler.errors).as("onError を呼ばないこと").isEmpty();
  }

  /** stop された observation の context と、onError の呼び出しを記録する。 */
  /* package */ static final class RecordingHandler
      implements ObservationHandler<Observation.Context> {

    /** stop された context。 */
    private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

    /** onError を受けた context。 */
    private final List<Observation.Context> errors = new CopyOnWriteArrayList<>();

    @Override
    public void onStop(final Observation.Context context) {
      stopped.add(context);
    }

    @Override
    public void onError(final Observation.Context context) {
      errors.add(context);
    }

    @Override
    public boolean supportsContext(final Observation.Context context) {
      return true;
    }

    /* package */ List<Observation.Context> queries() {
      return stopped.stream().filter(context -> "jooq.query".equals(context.getName())).toList();
    }
  }

  /** 記録用の handler を持つ registry。jOOQ のスライスに registry がなくても動くよう、ここで作る。 */
  @TestConfiguration(proxyBeanMethods = false)
  /* package */ static class Registry {

    @Bean
    /* package */ RecordingHandler recordingHandler() {
      return new RecordingHandler();
    }

    @Bean
    @Primary
    /* package */ ObservationRegistry testObservationRegistry(final RecordingHandler handler) {
      final ObservationRegistry registry = ObservationRegistry.create();
      registry.observationConfig().observationHandler(handler);
      return registry;
    }
  }
}
