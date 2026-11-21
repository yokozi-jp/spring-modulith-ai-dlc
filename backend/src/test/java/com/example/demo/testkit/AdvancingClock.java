package com.example.demo.testkit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 読むたびに 1 マイクロ秒進む時計。
 *
 * <p>{@link Clock#fixed} は何度読んでも同じ値を返し、時計を 2 回読む誤りを隠す。この時計なら、2 回読んだ値は必ず違う（ADR-109）。
 */
public final class AdvancingClock extends Clock {

  /** 最初に返す時刻。 */
  private final Instant start;

  /** これまでに読まれた回数。 */
  private final AtomicLong reads = new AtomicLong();

  /** 最初に {@code start} を返す時計を作る。{@code start} はマイクロ秒の精度にする。 */
  public AdvancingClock(final Instant start) {
    super();
    this.start = start;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(final ZoneId zone) {
    throw new UnsupportedOperationException("AdvancingClock is UTC only");
  }

  @Override
  public Instant instant() {
    return start.plusNanos(reads.getAndIncrement() * 1_000L);
  }
}
