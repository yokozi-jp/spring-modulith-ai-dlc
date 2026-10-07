package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.jooq.tables.FixtureItemTable;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.Field;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * 非同期のモジュールのイベントリスナーの中でも、共通カラムの trace ID と pgm_cd を取れることを検証する。
 *
 * <p>trace か pgm_cd がなければ共通処理は登録を失敗させるため、イベントを受けた処理の INSERT が常に失敗しないことを確かめる。 pgm_cd
 * は、アプリケーションのコンテキストに登録された {@link PgmCdAspect} が束縛する。
 *
 * <p>Listener が書く {@code *_by} は、利用者の操作を起点とする場合も処理の名前（{@code *_pgm_cd}）であることも確かめる
 * （docs/database/postgresql-common-columns.md）。
 */
@ApplicationModuleTest
@Import({SharedTestConfiguration.class, CommonColumnsListenerTraceTest.ProbeConfiguration.class})
@ExtendWith(CleanGeneratedTablesExtension.class)
class CommonColumnsListenerTraceTest {

  /** Listener の {@code *_pgm_cd}。 */
  private static final String LISTENER_PGM_CD = "shared.TraceProbe";

  /** 発行側の span を作る observation registry。 */
  @Autowired private ObservationRegistry observationRegistry;

  /** 発行側の現在の span を確かめる。 */
  @Autowired private Tracer tracer;

  /** リスナーで組み立てた trace ID か例外を受け取る。 */
  @Autowired private TraceProbeListener probe;

  @Test
  @DisplayName("@ApplicationModuleListener の中で forInsert が現在の trace ID と Listener の pgm_cd を登録できる")
  void listenerObtainsTraceId(final Scenario scenario) {
    final String publisherTraceId = publishInSpan(scenario);

    assertThat(probe.result())
        .as("リスナーで登録する created_tx_id、created_pgm_cd、created_by。例外ではなく、発行側と同じ trace ID であること")
        .isEqualTo(List.of(publisherTraceId, LISTENER_PGM_CD, LISTENER_PGM_CD));
  }

  @Test
  @DisplayName("利用者の要求の中で発行したイベントでも、Listener の created_by は利用者の sub ではなく Listener の pgm_cd になる")
  void listenerOperatorIsPgmCdEvenWhenPublishedByUser(final Scenario scenario) {
    final Instant issuedAt = Instant.parse("2026-10-03T00:00:00Z");
    final OidcIdToken idToken =
        OidcIdToken.withTokenValue("id-token")
            .subject("user-sub")
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300))
            .build();
    final DefaultOidcUser user =
        new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
    SecurityContextHolder.getContext()
        .setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "web"));
    try {
      final String publisherTraceId = publishInSpan(scenario);

      assertThat(probe.result())
          .as("リスナーで登録する created_tx_id、created_pgm_cd、created_by")
          .isEqualTo(List.of(publisherTraceId, LISTENER_PGM_CD, LISTENER_PGM_CD));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  /** 発行側の span の中でイベントを発行し、Listener が動くまで待って、発行側の trace ID を返す。 */
  private String publishInSpan(final Scenario scenario) {
    probe.reset();
    final AtomicReference<String> publisherTraceId = new AtomicReference<>();

    Observation.createNotStarted("trace-probe", observationRegistry)
        .observe(
            () -> {
              final Span publisherSpan =
                  Objects.requireNonNull(tracer.currentSpan(), "発行側に現在の span があること");
              publisherTraceId.set(publisherSpan.context().traceId());
              scenario
                  .publish(new TraceProbeEvent())
                  .andWaitForStateChange(probe::result)
                  .andVerify(result -> {});
            });
    return publisherTraceId.get();
  }

  /** リスナーを起動するためのイベント。 */
  /* package */ record TraceProbeEvent() {}

  /** モジュールのイベントリスナーで共通カラムを組み立て、trace ID、pgm_cd、作成者か例外を記録する。 */
  // 非同期とトランザクションのプロキシを作れるよう、final にしない。
  @SuppressWarnings({"PMD.ShortMethodName", "PMD.PublicMemberInNonPublicType"})
  /* package */ static class TraceProbeListener {

    /** 検証対象の共通処理。 */
    private final CommonColumns commonColumns;

    /** リスナーで得た trace ID、pgm_cd、作成者か例外。 */
    private final AtomicReference<@Nullable Object> observed = new AtomicReference<>();

    /* package */ TraceProbeListener(final CommonColumns commonColumns) {
      this.commonColumns = commonColumns;
    }

    /** イベントを受けて、INSERT の共通カラムを組み立てる。 */
    // PgmCdAspect の pointcut の対象にするため、public にする。
    @ApplicationModuleListener
    public void on(final TraceProbeEvent event) {
      try {
        final Map<Field<?>, Object> values = commonColumns.forInsert(FixtureItemTable.FIXTURE_ITEM);
        observed.set(
            List.of(
                values.get(FixtureItemTable.FIXTURE_ITEM.CREATED_TX_ID),
                values.get(FixtureItemTable.FIXTURE_ITEM.CREATED_PGM_CD),
                values.get(FixtureItemTable.FIXTURE_ITEM.CREATED_BY)));
      } catch (IllegalStateException exception) {
        observed.set(exception);
      }
    }

    /** 前のテストの結果を消す。 */
    /* package */ void reset() {
      observed.set(null);
    }

    /** まだリスナーが動いていなければ null を返す。 */
    /* package */ @Nullable Object result() {
      return observed.get();
    }
  }

  /** リスナーの Bean を登録する。 */
  @TestConfiguration(proxyBeanMethods = false)
  /* package */ static class ProbeConfiguration {

    @Bean
    /* package */ TraceProbeListener traceProbeListener(final CommonColumns commonColumns) {
      return new TraceProbeListener(commonColumns);
    }
  }
}
