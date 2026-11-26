package com.example.demo.shared.eventresubmission;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.events.ResubmissionPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;

/**
 * 外部システムの停止と回復のシナリオ（docs/nfr/quality-attribute-scenarios.md の「外部システムの停止と回復」）が許容する停止の長さが、{@code
 * application.yaml} の再投入の方針の待ち時間の和と一致することを検証する。
 *
 * <p>倍率、待ち時間の上限、回数の上限は {@code application.yaml} から読み、{@code wait-age} だけは {@code .env.example}
 * の開始値（PT1M）に置く。 設定を変えるとこのテストが失敗するので、シナリオの数値を同じ変更で直す。
 */
// テストのリスナーの受信メソッドは on と命名する規約のため。
// ApplicationContextRunner の run に渡す callback の中で assert するため、PMD はテストメソッドの assert を検出できない。
@SuppressWarnings({"PMD.ShortMethodName", "PMD.UnitTestShouldIncludeAssert"})
class ResubmissionDowntimeToleranceTest {

  /** 方針を付けていないリスナーの ID。default の方針に従う。 */
  private static final String DEFAULT_LISTENER_ID = "default-listener";

  /** payment の方針を付けたリスナーの ID。 */
  private static final String PAYMENT_LISTENER_ID = "payment-listener";

  /** 最初の配信が失敗した時刻。マイクロ秒の精度にする。 */
  private static final Instant FIRST_FAILURE = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** 既定の設定での、1 回目から最後の再投入までの前の待ち時間。シナリオの表と同じ値を書く。 */
  private static final List<Duration> WAITS =
      List.of(
          Duration.ofMinutes(1),
          Duration.ofMinutes(2),
          Duration.ofMinutes(4),
          Duration.ofMinutes(8),
          Duration.ofMinutes(16));

  /** {@link #WAITS} の和。シナリオが許容する停止の長さ。 */
  private static final Duration TOLERATED_OUTAGE = Duration.ofMinutes(31);

  /** application.yaml を読み、環境変数の代わりに .env.example の開始値を置く文脈。 */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(PropertiesOnly.class)
          .withBean(PaymentListener.class)
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withPropertyValues(
              "event-publication.resubmission.wait-age=PT1M",
              "event-publication.resubmission.in-flight-staleness=PT25M",
              "event-publication.resubmission.interval=PT30S");

  @ParameterizedTest(name = "listener={0}, policy={1}")
  @CsvSource({DEFAULT_LISTENER_ID + ", default", PAYMENT_LISTENER_ID + ", payment"})
  @DisplayName("再投入は、待ち時間 1、2、4、8、16 分の後に 5 回まで行われ、待ち時間の和 31 分が許容する停止の長さと一致する")
  void toleratedOutageEqualsSumOfWaits(final String listenerId, final String policy) {
    runner.run(context -> {
      final ResubmissionPolicies policies =
          new ResubmissionPolicies(
              context.getBeanFactory(),
              context.getBean(EventPublicationResubmissionConfig.Properties.class),
              context.getBean(EventPublicationResubmissionConfig.AbandonProperties.class));
      assertThat(policies.nameOf(listenerId)).as("リスナーの方針").isEqualTo(policy);
      Duration total = Duration.ZERO;
      Instant lastAttempt = FIRST_FAILURE;

      for (int resubmission = 1; resubmission <= WAITS.size(); resubmission++) {
        final Duration wait = WAITS.get(resubmission - 1);
        final EventPublication failed = failed(listenerId, resubmission, lastAttempt);
        assertThat(policies.isDue(failed, lastAttempt.plus(wait)))
            .as("%d 回目の再投入の、待ち時間ちょうど", resubmission)
            .isFalse();
        assertThat(policies.isDue(failed, lastAttempt.plus(wait).plus(1, ChronoUnit.MICROS)))
            .as("%d 回目の再投入の、待ち時間の 1 マイクロ秒後", resubmission)
            .isTrue();
        total = total.plus(wait);
        lastAttempt = lastAttempt.plus(wait);
      }

      assertThat(total).as("待ち時間の和").isEqualTo(TOLERATED_OUTAGE);
      assertThat(
              policies.isDue(
                  failed(listenerId, WAITS.size() + 1, lastAttempt),
                  lastAttempt.plus(Duration.ofDays(1))))
          .as("回数の上限の次の再投入")
          .isFalse();
    });
  }

  /** 最初の配信から {@code lastAttempt} まで失敗が続き、{@code completionAttempts} 回試行した FAILED の出版。 */
  private static EventPublication failed(
      final String listenerId, final int completionAttempts, final Instant lastAttempt) {
    final TargetEventPublication publication =
        Mockito.mock(TargetEventPublication.class, Mockito.CALLS_REAL_METHODS);
    Mockito.doReturn(PublicationTargetIdentifier.of(listenerId))
        .when(publication)
        .getTargetIdentifier();
    return ResubmissionTestSupport.stubPublication(
        publication, UUID.randomUUID(), completionAttempts, FIRST_FAILURE, lastAttempt);
  }

  /** 再投入の設定値だけを bean にする。 */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    EventPublicationResubmissionConfig.Properties.class,
    EventPublicationResubmissionConfig.AbandonProperties.class
  })
  /* package */ static class PropertiesOnly {}

  /** 決済の方針を付けたリスナー。 */
  /* package */ static class PaymentListener {

    @ApplicationModuleListener(id = PAYMENT_LISTENER_ID)
    @ResubmissionPolicy("payment")
    /* package */ void on(final String event) {
      // 呼ばれない。
    }
  }
}
