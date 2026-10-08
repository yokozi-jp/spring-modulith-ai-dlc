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
    final String publisherTraceId = publishInSpan(scenario, false);

    assertThat(probe.result())
        .as(
            "リスナーで登録する created_tx_id、created_pgm_cd、created_by、updated_by。例外ではなく、発行側と同じ trace ID であること")
        .isEqualTo(List.of(publisherTraceId, LISTENER_PGM_CD, LISTENER_PGM_CD, LISTENER_PGM_CD));
  }

  @Test
  @DisplayName("Listener の中に利用者の認証があっても、created_by と updated_by は Listener の pgm_cd になる")
  void listenerOperatorIsPgmCdEvenWithUserAuthentication(final Scenario scenario) {
    final String publisherTraceId = publishInSpan(scenario, true);

    assertThat(probe.result())
        .as("リスナーで登録する created_tx_id、created_pgm_cd、created_by、updated_by")
        .isEqualTo(List.of(publisherTraceId, LISTENER_PGM_CD, LISTENER_PGM_CD, LISTENER_PGM_CD));
  }

  /**
   * 発行側の span の中でイベントを発行し、Listener が動くまで待って、発行側の trace ID を返す。
   *
   * <p>{@code asUser} なら、Listener は自身の中で利用者の認証を立ててから共通カラムを組み立てる。
   */
  private String publishInSpan(final Scenario scenario, final boolean asUser) {
    probe.reset();
    final AtomicReference<String> publisherTraceId = new AtomicReference<>();

    Observation.createNotStarted("trace-probe", observationRegistry)
        .observe(
            () -> {
              final Span publisherSpan =
                  Objects.requireNonNull(tracer.currentSpan(), "発行側に現在の span があること");
              publisherTraceId.set(publisherSpan.context().traceId());
              scenario
                  .publish(new TraceProbeEvent(asUser))
                  .andWaitForStateChange(probe::result)
                  .andVerify(result -> {});
            });
    return publisherTraceId.get();
  }

  /** リスナーを起動するためのイベント。{@code asUser} なら、Listener の中で利用者の認証を立てる。 */
  /* package */ record TraceProbeEvent(boolean asUser) {}

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

    /**
     * イベントを受けて、INSERT と UPDATE の共通カラムを組み立てる。
     *
     * <p>認証が Listener のスレッドへ渡るかは非同期の実行の設定に依存するため（ADR-051）、利用者の認証は Listener の中で立てる。
     */
    // PgmCdAspect の pointcut の対象にするため、public にする。
    @ApplicationModuleListener
    public void on(final TraceProbeEvent event) {
      try {
        if (event.asUser()) {
          SecurityContextHolder.getContext().setAuthentication(userAuthentication());
        }
        final Map<Field<?>, Object> inserted =
            commonColumns.forInsert(FixtureItemTable.FIXTURE_ITEM);
        final Map<Field<?>, Object> updated =
            commonColumns.forUpdate(FixtureItemTable.FIXTURE_ITEM);
        observed.set(
            List.of(
                inserted.get(FixtureItemTable.FIXTURE_ITEM.CREATED_TX_ID),
                inserted.get(FixtureItemTable.FIXTURE_ITEM.CREATED_PGM_CD),
                inserted.get(FixtureItemTable.FIXTURE_ITEM.CREATED_BY),
                updated.get(FixtureItemTable.FIXTURE_ITEM.UPDATED_BY)));
      } catch (IllegalStateException exception) {
        observed.set(exception);
      } finally {
        SecurityContextHolder.clearContext();
      }
    }

    /** sub が {@code user-sub} の OIDC の利用者の認証を作る。 */
    private static OAuth2AuthenticationToken userAuthentication() {
      final Instant issuedAt = Instant.parse("2026-10-03T00:00:00Z");
      final OidcIdToken idToken =
          OidcIdToken.withTokenValue("id-token")
              .subject("user-sub")
              .issuedAt(issuedAt)
              .expiresAt(issuedAt.plusSeconds(300))
              .build();
      final DefaultOidcUser user =
          new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
      return new OAuth2AuthenticationToken(user, user.getAuthorities(), "web");
    }

    // ponytail: probe はテストクラスで共有し、逐次実行を前提にする。並列実行を入れるときはテストごとに probe を分ける。
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
