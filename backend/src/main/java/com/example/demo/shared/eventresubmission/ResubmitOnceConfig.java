package com.example.demo.shared.eventresubmission;

import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 手での 1 回の再投入（{@link EventPublicationResubmitOnce}）の起動を構成する（ADR-079）。
 *
 * <p>対象の設定（{@value #RESUBMIT_ONCE_TARGET}）があるときだけ、起動の後に 1 回だけ再投入して JVM を終える。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResubmitOnceProperties.class)
class ResubmitOnceConfig {

  /** 手での 1 回の再投入の対象の設定のキー。値があるときだけ、起動の後に 1 回だけ再投入して終了する。 */
  /* package */ static final String RESUBMIT_ONCE_TARGET = "event-publication.resubmit-once.target";

  /**
   * 手での 1 回の再投入が求められているか。Runner の生成と、Web と定期の実行の停止は、この述語だけで決める。
   *
   * <p>対象が空白なら通常の起動である。空白以外の値は、{@code false} を含めて手での再投入の求めとして扱う。 不正な値は Runner が {@link
   * IllegalArgumentException} で受けて終了コード 1 にするため、何もせずに起動する状態を作らない。
   */
  /* package */ static boolean isResubmitOnceRequested(final PropertyResolver environment) {
    return !environment.getProperty(RESUBMIT_ONCE_TARGET, "").isBlank();
  }

  /** {@link #isResubmitOnceRequested} を {@code @Conditional} から呼ぶための条件。 */
  /* package */ static class ResubmitOnceRequested implements Condition {

    @Override
    public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
      return isResubmitOnceRequested(context.getEnvironment());
    }
  }

  /** 手での 1 回の再投入が求められているときだけ、起動の後に 1 回だけ再投入し、終了コードを返して JVM を終える。 */
  @Bean
  @Conditional(ResubmitOnceRequested.class)
  /* package */ ApplicationRunner resubmitOnceRunner(
      final EventPublicationResubmitOnce job,
      final ResubmitOnceProperties properties,
      final ConfigurableApplicationContext context) {
    return args -> {
      final int exitCode = resubmitOnceExitCode(job, properties.target());
      awaitRunningListeners(context);
      exit(SpringApplication.exit(context, () -> exitCode));
    };
  }

  /**
   * 文脈を閉じる前に {@code applicationTaskExecutor} を閉じ、処理中のリスナーの完了を待つ。
   *
   * <p>待つ上限は {@code spring.task.execution.shutdown.await-termination-period} である（{@link
   * ResubmitOnceEnvironment}）。 文脈を閉じるときに任せないのは、executor が DataSource より先に作られ、後で閉じられるためである。
   * 任せると、待っている間のリスナーは閉じた DataSource で失敗する。 このアプリは {@code spring.threads.virtual.enabled=true}
   * で動くため、SimpleAsyncTaskExecutor だけを閉じる。
   */
  // executor は Bean であり、ここで閉じるのは文脈より先に待つためで、使い終えた資源の閉じ忘れではない。
  @SuppressWarnings("PMD.CloseResource")
  /* package */ static void awaitRunningListeners(final ApplicationContext context) {
    if (context.getBean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
        instanceof SimpleAsyncTaskExecutor executor) {
      executor.close();
    }
  }

  /** 全件を完了したら 0、それ以外と例外では 1 を返す。 */
  // 運用者が起動した 1 回の実行の失敗を、例外の種類によらず終了コードで返すため、すべての実行時の例外を受ける。
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  /* package */ static int resubmitOnceExitCode(
      final EventPublicationResubmitOnce job, final @Nullable String target) {
    try {
      final boolean succeeded =
          job.run(EventPublicationResubmitOnce.Target.parse(Objects.requireNonNull(target)))
              .succeeded();
      return succeeded ? 0 : 1;
    } catch (RuntimeException e) {
      log.atError().setCause(e).log("Event publication resubmit-once failed");
      return 1;
    }
  }

  // 1 回の実行の後は、OTLP の送信のスレッドが残っても終わるよう、JVM を明示して終える。
  @SuppressWarnings("PMD.DoNotTerminateVM")
  private static void exit(final int exitCode) {
    System.exit(exitCode);
  }

  /**
   * 手での 1 回の再投入の対象があるとき、Web のサーバーと定期の実行を止める。
   *
   * <p>同じイメージを 1 回だけ起動する入口であり、対象の設定 1 つで、HTTP の待ち受けと、定期の再投入、期限を過ぎた冪等性キーの掃除、archive
   * の行の保持期限の削除を動かさないようにする。 環境変数の値より優先するため、プロパティの先頭に足す。
   *
   * <p>終了の前に、処理中のリスナーの完了を最大 20 秒待つ（{@link ResubmitOnceConfig#awaitRunningListeners}）。
   * 処理を待つ上限を過ぎたり、他のインスタンスが出版を FAILED に戻したりして終わることがあり、そのまま JVM を終えるとリスナーが途中で切れるためである。 仮想スレッドの {@code
   * applicationTaskExecutor}（SimpleAsyncTaskExecutor）は、Spring Boot がこの設定を {@code
   * taskTerminationTimeout} に渡す。 20 秒は、{@code spring.lifecycle.timeout-per-shutdown-phase} の 30 秒と
   * ECS の既定の stopTimeout の 30 秒より短く、1 回の試行のリスナーの最長より長い。
   */
  /* package */ static class ResubmitOnceEnvironment implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(
        final ConfigurableEnvironment environment, final SpringApplication application) {
      if (!isResubmitOnceRequested(environment)) {
        return;
      }
      environment
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "resubmitOnce",
                  Map.of(
                      "spring.main.web-application-type",
                      "none",
                      "event-publication.resubmission.enabled",
                      "false",
                      "idempotency-key.purge.enabled",
                      "false",
                      "event-publication.archive-retention.enabled",
                      "false",
                      "spring.task.execution.shutdown.await-termination",
                      "true",
                      "spring.task.execution.shutdown.await-termination-period",
                      "20s")));
    }
  }
}
