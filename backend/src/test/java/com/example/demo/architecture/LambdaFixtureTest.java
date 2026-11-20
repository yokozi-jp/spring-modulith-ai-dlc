package com.example.demo.architecture;

import static com.example.demo.architecture.ViolatingFixtures.VIOLATING;
import static com.example.demo.architecture.ViolatingFixtures.VIOLATING_CLASSES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit がラムダの本体の呼び出しを外側のメソッドの呼び出しとして数えることに、規則が頼る箇所を固定する。
 *
 * <p>ArchUnit の版を上げてこの扱いが変わると、このテストが落ちる。
 */
class LambdaFixtureTest {

  @Test
  @DisplayName("aggregateRootInsertsGoThroughTableWriter は add のラムダの中の INSERT を add の違反として検出する")
  void insertInsideLambdaOfAddIsDetected() {
    final Throwable thrown =
        catchThrowable(() ->
            TableWriterArchTest.aggregateRootInsertsGoThroughTableWriterRule()
                .check(VIOLATING_CLASSES));

    assertThat(thrown).isInstanceOf(AssertionError.class);
    assertThat(thrown.getMessage().lines())
        .anyMatch(line ->
            line.contains("JooqOrderLambdaDraftRepository.add(") && line.contains(".execute()"));
  }

  @Test
  @DisplayName("commandHandlersEnsureScreenLockNo は handle のラムダの中の ensureLockNo を満たすものとして扱う")
  void ensureLockNoInsideLambdaOfHandleSatisfiesTheRule() {
    final Throwable thrown =
        catchThrowable(() ->
            TableWriterArchTest.commandHandlersEnsureScreenLockNoRule(VIOLATING)
                .check(VIOLATING_CLASSES));

    assertThat(thrown).isInstanceOf(AssertionError.class);
    assertThat(thrown.getMessage()).doesNotContain("LambdaEnsureCommandHandler");
  }
}
