package com.example.demo.testkit;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/** 定期の実行の設定のテストが、登録されたタスクを読む補助。 */
public final class ScheduledTasks {

  private ScheduledTasks() {}

  /** 設定値の検証と定期の実行の自動構成だけを読み込む runner を返す。 */
  public static ApplicationContextRunner schedulingRunner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ValidationAutoConfiguration.class, TaskSchedulingAutoConfiguration.class));
  }

  /** コンテキストに登録された、固定の遅延のタスクを返す。 */
  public static List<FixedDelayTask> fixedDelayTasks(final ApplicationContext context) {
    return context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
        .map(ScheduledTask::getTask)
        .filter(FixedDelayTask.class::isInstance)
        .map(FixedDelayTask.class::cast)
        .toList();
  }

  /** 期限を過ぎた冪等性キーの掃除のように、定期のタスクを持たずに {@code @EnableScheduling} だけを付ける別の定期のジョブ。 */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  public static class AnotherScheduledJob {}
}
