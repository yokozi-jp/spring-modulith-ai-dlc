package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import archfixture.sleep.ThreadSleepCall;
import archfixture.sleep.ThreadSleepReference;
import archfixture.sleep.TimeUnitSleepCall;
import archfixture.sleep.TimeUnitSleepReference;
import archfixture.sleep.UnrelatedSleep;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link TestConventionsArchTest#sleepIsNotUsedInTests} が、固定時間の待機だけを直接の呼び出しとメソッド参照の両方で検出することを固定する。
 */
class SleepRuleFixtureTest {

  @ParameterizedTest
  @ValueSource(
      classes = {
        ThreadSleepCall.class,
        TimeUnitSleepCall.class,
        ThreadSleepReference.class,
        TimeUnitSleepReference.class
      })
  @DisplayName("sleepIsNotUsedInTests は Thread と TimeUnit の sleep を呼び出しでもメソッド参照でも検出する")
  void detectsFixedTimeSleep(final Class<?> violating) {
    assertThatThrownBy(() ->
            TestConventionsArchTest.sleepIsNotUsedInTests.check(
                new ClassFileImporter().importClasses(violating)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(violating.getName());
  }

  @org.junit.jupiter.api.Test
  @DisplayName("sleepIsNotUsedInTests は固定時間の待機でない同名の sleep を検出しない")
  void ignoresUnrelatedSleep() {
    assertThatCode(() ->
            TestConventionsArchTest.sleepIsNotUsedInTests.check(
                new ClassFileImporter().importClasses(UnrelatedSleep.class)))
        .doesNotThrowAnyException();
  }
}
