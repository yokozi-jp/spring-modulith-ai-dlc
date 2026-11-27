package com.example.demo.shared.eventresubmission;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.eventresubmission.EventPublicationResubmitOnce.Target;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.env.MockEnvironment;

/** {@link ResubmitOnceConfig} の起動の条件、Web と定期の実行を止める設定、終了コード、終了の前のリスナーの完了待ちを検証する（ADR-079）。 */
// ApplicationContextRunner の run に渡す callback の中で assert するため、PMD はテストメソッドの assert を検出できない。
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class ResubmitOnceConfigTest {

  /** 定期の再投入の有効と無効のキー。 */
  private static final String ENABLED = "event-publication.resubmission.enabled";

  /** 手での 1 回の再投入の対象のキー。 */
  private static final String RESUBMIT_ONCE_TARGET = ResubmitOnceConfig.RESUBMIT_ONCE_TARGET;

  /** 終了の前に executor のタスクの完了を待つかのキー。 */
  private static final String AWAIT_TERMINATION =
      "spring.task.execution.shutdown.await-termination";

  /** 終了の前に executor のタスクの完了を待つ上限のキー。 */
  private static final String AWAIT_TERMINATION_PERIOD =
      "spring.task.execution.shutdown.await-termination-period";

  /** 起動の設定だけを読み込み、妥当な設定値を与える runner。 */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(ResubmitOnceConfig.class)
          .withPropertyValues("event-publication.resubmit-once.completion-timeout=PT10M");

  /** 期限を過ぎた冪等性キーの掃除を有効にする設定のキー。掃除の構成の条件から読み、キーを変えても、このテストが古い文字列を確かめ続けないようにする。 */
  private static String purgeEnabledKey() {
    try {
      return Class.forName(
              "com.example.demo.shared.persistence.IdempotencyKeyPurgeConfig$Scheduling")
          .getAnnotation(ConditionalOnBooleanProperty.class)
          .value()[0];
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("掃除の構成が見つからない", e);
    }
  }

  @Test
  @DisplayName("手での 1 回の再投入の対象があると、Web のサーバーと定期の実行（再投入、冪等性キーの掃除、archive の保持期限の削除）を止め、なければ何もしない")
  void resubmitOnceEnvironmentDisablesWebAndScheduling() {
    final ResubmitOnceConfig.ResubmitOnceEnvironment postProcessor =
        new ResubmitOnceConfig.ResubmitOnceEnvironment();
    final MockEnvironment once =
        new MockEnvironment()
            .withProperty("event-publication.resubmit-once.target", "policy:payment")
            .withProperty(ENABLED, "true");
    final MockEnvironment normal =
        new MockEnvironment().withProperty(ENABLED, Boolean.TRUE.toString());

    postProcessor.postProcessEnvironment(once, new SpringApplication());
    postProcessor.postProcessEnvironment(normal, new SpringApplication());

    assertThat(once.getProperty("spring.main.web-application-type")).isEqualTo("none");
    assertThat(once.getProperty(ENABLED)).isEqualTo("false");
    assertThat(once.getProperty(purgeEnabledKey())).isEqualTo("false");
    assertThat(
            once.getProperty(
                EventPublicationArchiveRetentionConfig.Scheduling.class.getAnnotation(
                        ConditionalOnBooleanProperty.class)
                    .value()[0]))
        .isEqualTo("false");
    assertThat(once.getProperty(AWAIT_TERMINATION)).isEqualTo("true");
    assertThat(once.getProperty(AWAIT_TERMINATION_PERIOD)).isEqualTo("20s");
    assertThat(normal.getProperty("spring.main.web-application-type")).isNull();
    assertThat(normal.getProperty(ENABLED)).isEqualTo("true");
    assertThat(normal.getProperty(AWAIT_TERMINATION)).isNull();
    assertThat(normal.getProperty(AWAIT_TERMINATION_PERIOD)).isNull();
  }

  @Test
  @DisplayName("手での 1 回の再投入の終了の設定では、文脈を閉じる前に applicationTaskExecutor の処理中のリスナーの完了を待つ")
  void resubmitOnceShutdownWaitsForRunningListener() {
    final AtomicBoolean running = new AtomicBoolean();
    final AtomicBoolean exiting = new AtomicBoolean();
    final AtomicBoolean done = new AtomicBoolean();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
        .withPropertyValues(
            "spring.threads.virtual.enabled=true",
            "spring.task.execution.shutdown.await-termination=true",
            "spring.task.execution.shutdown.await-termination-period=20s")
        .run(context -> {
          // 終了を始めてから終わるタスクで、処理中のリスナーを表す。
          context
              .getBean("applicationTaskExecutor", TaskExecutor.class)
              .execute(() -> {
                running.set(true);
                Awaitility.await("終了を始める").atMost(Duration.ofSeconds(10)).untilTrue(exiting);
                done.set(true);
              });

          Awaitility.await("リスナーが動き始める").atMost(Duration.ofSeconds(10)).untilTrue(running);
          exiting.set(true);
          ResubmitOnceConfig.awaitRunningListeners(context);

          assertThat(done).as("文脈を閉じる前のリスナーの完了").isTrue();
        });
  }

  @Test
  @DisplayName("手での 1 回の再投入の起動は、spring.factories から読まれる")
  void resubmitOnceEnvironmentIsRegistered() {
    // 他の EnvironmentPostProcessor は Spring Boot が渡す引数を要るため、作れないものは飛ばす。
    assertThat(
            SpringFactoriesLoader.forDefaultResourceLocation()
                .load(
                    EnvironmentPostProcessor.class,
                    SpringFactoriesLoader.ArgumentResolver.none(),
                    SpringFactoriesLoader.FailureHandler.handleMessage((message, failure) -> {})))
        .hasAtLeastOneElementOfType(ResubmitOnceConfig.ResubmitOnceEnvironment.class);
  }

  @ParameterizedTest(name = "[{0}] 通常の起動: {1}")
  @CsvSource(
      value = {
        "'', true",
        "' ', true",
        "false, false",
        "FALSE, false",
        "all, false",
        "policy:payment, false"
      },
      quoteCharacter = '\'')
  @DisplayName("手での 1 回の再投入の対象が空白でなければ、Web と定期の実行を止め、runner も作る（false も同じ）")
  void resubmitOnceGatesAgree(final String target, final boolean normalStartup) {
    final MockEnvironment environment =
        new MockEnvironment()
            .withProperty(RESUBMIT_ONCE_TARGET, target)
            .withProperty(ENABLED, Boolean.TRUE.toString());
    new ResubmitOnceConfig.ResubmitOnceEnvironment()
        .postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("spring.main.web-application-type") == null)
        .as("Web を止めない")
        .isEqualTo(normalStartup);
    assertThat(environment.getProperty(ENABLED)).isEqualTo(Boolean.toString(normalStartup));
    assertThat(ResubmitOnceConfig.isResubmitOnceRequested(environment)).isEqualTo(!normalStartup);
    runner
        .withBean(
            EventPublicationResubmitOnce.class,
            () -> Mockito.mock(EventPublicationResubmitOnce.class))
        .withInitializer(context ->
            context
                .getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("target", Map.of(RESUBMIT_ONCE_TARGET, target))))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(
                  context.getBeansOfType(ApplicationRunner.class).containsKey("resubmitOnceRunner"))
              .as("runner の bean")
              .isEqualTo(!normalStartup);
        });
  }

  @Test
  @DisplayName("手での 1 回の再投入の対象が設定されていなければ、runner を作らず、Web と定期の実行を止めない")
  void unsetTargetIsNormalStartup() {
    final MockEnvironment environment =
        new MockEnvironment().withProperty(ENABLED, Boolean.TRUE.toString());

    assertThat(ResubmitOnceConfig.isResubmitOnceRequested(environment)).isFalse();
    runner.run(context -> assertThat(context).doesNotHaveBean("resubmitOnceRunner"));
  }

  @Test
  @DisplayName("手での 1 回の再投入の終了コードは、すべて完了なら 0、未完了や例外なら 1 である")
  void resubmitOnceExitCode() {
    final EventPublicationResubmitOnce job = Mockito.mock(EventPublicationResubmitOnce.class);
    Mockito.when(job.run(Target.parse("all")))
        .thenReturn(new EventPublicationResubmitOnce.Outcome(2, 2, 0, 0));
    Mockito.when(job.run(Target.parse("policy:payment")))
        .thenReturn(new EventPublicationResubmitOnce.Outcome(2, 1, 1, 0));
    Mockito.when(job.run(Target.parse("policy:missing")))
        .thenThrow(new IllegalArgumentException("unknown"));

    assertThat(ResubmitOnceConfig.resubmitOnceExitCode(job, "all")).isZero();
    assertThat(ResubmitOnceConfig.resubmitOnceExitCode(job, "policy:payment")).isEqualTo(1);
    assertThat(ResubmitOnceConfig.resubmitOnceExitCode(job, "policy:missing")).isEqualTo(1);
    assertThat(ResubmitOnceConfig.resubmitOnceExitCode(job, "OrderConfirmed"))
        .as("読めない対象は、job を呼ばずに 1 を返す")
        .isEqualTo(1);
    assertThat(ResubmitOnceConfig.resubmitOnceExitCode(job, null)).isEqualTo(1);
    Mockito.verify(job, Mockito.times(3)).run(Mockito.any());
  }
}
