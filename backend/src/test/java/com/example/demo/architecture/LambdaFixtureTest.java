package com.example.demo.architecture;

import static com.example.demo.architecture.ViolatingFixtures.VIOLATING;
import static com.example.demo.architecture.ViolatingFixtures.VIOLATING_CLASSES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import archfixture.violating.ordering.application.ApproveOrderCommand;
import archfixture.violating.ordering.application.LambdaEnsureCommandHandler;
import archfixture.violating.ordering.infrastructure.persistence.JooqOrderLambdaDraftRepository;
import archfixture.violating.ordering.infrastructure.persistence.JooqOrderRepository;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit がラムダの本体の呼び出しを外側のメソッドの呼び出しとして数えることに、規則が頼る箇所を固定する。
 *
 * <p>H1 は {@code DirectWriteFixtureTest} の {@code lambdaExecute} の行が固定する。ArchUnit
 * の版を上げてこの扱いが変わると、このテストが落ちる。
 */
class LambdaFixtureTest {

  @Test
  @DisplayName("aggregateRootInsertsGoThroughTableWriter は add のラムダの中の INSERT を add の違反として検出する")
  void insertInsideLambdaOfAddIsDetected() {
    assertThat(violationsOf(TableWriterArchTest.aggregateRootInsertsGoThroughTableWriterRule()))
        .contains(JooqOrderLambdaDraftRepository.class.getName() + ".add(");
  }

  @Test
  @DisplayName("aggregateMethodsDoNotUseUnversionedWrites は集約ルートを受け取るメソッドのラムダの中の updateWhere を検出する")
  void updateWhereInsideLambdaIsDetected() {
    assertThat(
            violationsOf(
                TableWriterArchTest.aggregateMethodsDoNotUseUnversionedWritesRule(VIOLATING)))
        .contains(JooqOrderRepository.class.getName() + ".updateLater(");
  }

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

    assertThat(handleCallsEnsureLockNo).as("ArchUnit が handle の呼び出しとして数える").isTrue();
    assertThat(violationsOf(TableWriterArchTest.commandHandlersEnsureScreenLockNoRule(VIOLATING)))
        .doesNotContain(LambdaEnsureCommandHandler.class.getName());
  }

  private static String violationsOf(final ArchRule rule) {
    final Throwable thrown = catchThrowable(() -> rule.check(VIOLATING_CLASSES));
    assertThat(thrown).isInstanceOf(AssertionError.class);
    return thrown.getMessage();
  }
}
