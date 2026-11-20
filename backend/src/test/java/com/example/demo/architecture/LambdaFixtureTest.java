package com.example.demo.architecture;

import static com.example.demo.architecture.ViolatingFixtures.VIOLATING;
import static com.example.demo.architecture.ViolatingFixtures.VIOLATING_CLASSES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import archfixture.violating.ordering.application.ApproveOrderCommand;
import archfixture.violating.ordering.application.LambdaEnsureCommandHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit がラムダの本体の呼び出しを外側のメソッドの呼び出しとして数えることに、R4 が頼る箇所を固定する。
 *
 * <p>H1 は {@code DirectWriteFixtureTest} の {@code lambdaExecute} の行が固定する。ArchUnit
 * の版を上げてこの扱いが変わると、このテストが落ちる。
 */
class LambdaFixtureTest {

  @Test
  @DisplayName(
      "commandHandlersEnsureScreenLockNo は handle のラムダの中の ensureLockNo を handle の呼び出しとして数える")
  void ensureLockNoInsideLambdaOfHandleSatisfiesTheRule() {
    final boolean handleCallsEnsureLockNo =
        VIOLATING_CLASSES
            .get(LambdaEnsureCommandHandler.class)
            .getMethod("handle", ApproveOrderCommand.class)
            .getMethodCallsFromSelf()
            .stream()
            .anyMatch(call -> "ensureLockNo".equals(call.getName()));
    final Throwable thrown =
        catchThrowable(() ->
            TableWriterArchTest.commandHandlersEnsureScreenLockNoRule(VIOLATING)
                .check(VIOLATING_CLASSES));

    assertThat(handleCallsEnsureLockNo).as("ArchUnit が handle の呼び出しとして数える").isTrue();
    assertThat(thrown).isInstanceOf(AssertionError.class);
    assertThat(thrown.getMessage()).doesNotContain(LambdaEnsureCommandHandler.class.getName());
  }
}
