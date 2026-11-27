package com.example.demo.shared.locking;

import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;

/**
 * ロックを取って定期に動くジョブの時間の設定。ジョブの設定値の {@code record} が実装する。
 *
 * <p>ロックを持つ下限は、間隔の半分にする。各インスタンスは自分の前の回の終わりから間隔を空けて動くため、下限を間隔と同じにすると、1 台のときも DB
 * の時刻とインスタンスの時刻の測り方の差で自分の次の回を省きうる。半分にして余裕を残す。
 */
public interface PeriodicLockTiming {

  /** 前の回の終わりから次の回までの間隔。 */
  Duration interval();

  /** ロックを持つ上限。実行が落ちて解放できなかったとき、この時間を過ぎると他のインスタンスが取れる。 */
  Duration lockAtMostFor();

  /** ロックを持つ下限。 */
  default Duration lockAtLeastFor() {
    return interval().dividedBy(2);
  }

  /** ロックを持つ上限が間隔より長いか。短いと、ロックを持つ下限（間隔の半分）が上限を超えうる。 */
  @AssertTrue(message = "lock-at-most-for must be longer than interval") default boolean isLockAtMostForLongerThanInterval() {
    return lockAtMostFor() == null
        || interval() == null
        || lockAtMostFor().compareTo(interval()) > 0;
  }
}
