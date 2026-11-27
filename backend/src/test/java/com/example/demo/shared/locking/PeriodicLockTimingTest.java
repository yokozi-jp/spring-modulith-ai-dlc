package com.example.demo.shared.locking;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ロックを取って定期に動くジョブの時間の規則を、Spring を起動せずに確かめる（ADR-079、ADR-126）。 */
class PeriodicLockTimingTest {

  /** 検証に使う、最小の実装。 */
  private record Timing(Duration interval, Duration lockAtMostFor) implements PeriodicLockTiming {}

  @Test
  @DisplayName("ロックを持つ下限は、間隔の半分である")
  void lockAtLeastForIsHalfOfInterval() {
    assertThat(new Timing(Duration.ofMinutes(10), Duration.ofHours(1)).lockAtLeastFor())
        .isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  @DisplayName("ロックを持つ上限が間隔より長ければ通り、同じか短ければ実装した record の検証で落ちる")
  void lockAtMostForMustBeLongerThanInterval() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      final Validator validator = factory.getValidator();

      assertThat(validator.validate(new Timing(Duration.ofHours(1), Duration.ofSeconds(3601))))
          .as("1 秒長い")
          .isEmpty();
      assertThat(validator.validate(new Timing(Duration.ofHours(1), Duration.ofHours(1))))
          .as("同じ")
          .singleElement()
          .satisfies(violation ->
              assertThat(violation.getPropertyPath())
                  .hasToString("lockAtMostForLongerThanInterval"));
      assertThat(validator.validate(new Timing(Duration.ofHours(1), Duration.ofMinutes(59))))
          .as("短い")
          .hasSize(1);
    }
  }

  @Test
  @DisplayName("どちらかが未設定のとき、この規則は落とさない（未設定は別の検証が落とす）")
  void missingValueIsLeftToOtherConstraints() {
    assertThat(new Timing(null, Duration.ofHours(1)).isLockAtMostForLongerThanInterval()).isTrue();
    assertThat(new Timing(Duration.ofHours(1), null).isLockAtMostForLongerThanInterval()).isTrue();
  }
}
