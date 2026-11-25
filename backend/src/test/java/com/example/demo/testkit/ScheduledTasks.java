package com.example.demo.testkit;

import java.util.List;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/** 定期の実行の設定のテストが、登録されたタスクを読む補助。 */
public final class ScheduledTasks {

  private ScheduledTasks() {}

  /** コンテキストに登録された、固定の遅延のタスクを返す。 */
  public static List<FixedDelayTask> fixedDelayTasks(final ApplicationContext context) {
    return context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
        .map(ScheduledTask::getTask)
        .filter(FixedDelayTask.class::isInstance)
        .map(FixedDelayTask.class::cast)
        .toList();
  }
}
