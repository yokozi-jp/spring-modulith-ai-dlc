package com.example.demo.shared.eventresubmission;

import com.example.demo.shared.events.ResubmissionPolicy;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.shared.tenancy.TenantScoped;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.modulith.events.ApplicationModuleListener;

/** 再投入のテストが共有する、テストのイベント、リスナー、差し替えられる時計、それらを登録する設定。 */
/* package */ final class ResubmissionProbes {

  /** 最初の出版の時刻。マイクロ秒の精度にする。 */
  /* package */ static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** テスト用のリスナーに付ける再投入の方針の名前。 */
  /* package */ static final String PROBE_POLICY = "probe";

  /** 最初の配信の失敗で諦める、テスト用のリスナーの方針の名前。 */
  /* package */ static final String ONCE_POLICY = "probe-once";

  /** 2 つのスレッドの完了と、リスナーが門で待つ上限の秒数。 */
  /* package */ static final long JOIN_TIMEOUT_SECONDS = 10;

  private ResubmissionProbes() {}

  /**
   * テストのイベント。
   *
   * @param id 出版を見分ける ID
   * @param tenantId イベントのテナント
   */
  /* package */ record ResubmissionProbe(String id, TenantId tenantId) implements TenantScoped {}

  /**
   * 最初の配信の失敗で諦める方針のリスナーが受けるテストのイベント。
   *
   * @param id 出版を見分ける ID
   * @param tenantId イベントのテナント
   */
  /* package */ record OnceProbe(String id, TenantId tenantId) implements TenantScoped {}

  /** 常に失敗し、イベントの ID ごとに呼び出しの回数を数える、最初の配信の失敗で諦める方針のリスナー。 */
  // @ApplicationModuleListener のトランザクションの proxy を作るため final にしない。受信メソッドは on と命名する規約のため。
  @SuppressWarnings("PMD.ShortMethodName")
  /* package */ static class OnceProbeListener {

    /** イベントの ID ごとの呼び出しの回数。テストの間で共有しないよう、ID で分ける。 */
    private final Map<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();

    @ApplicationModuleListener
    @ResubmissionPolicy(ONCE_POLICY)
    /* package */ void on(final OnceProbe event) {
      callCounts.computeIfAbsent(event.id(), id -> new AtomicInteger()).incrementAndGet();
      throw new IllegalStateException("once probe listener always fails: id=" + event.id());
    }

    /* package */ int calls(final String id) {
      final @Nullable AtomicInteger count = callCounts.get(id);
      return count == null ? 0 : count.get();
    }
  }

  /** 呼び出しの回数を数え、決めた回数だけ失敗し、門が閉じていれば開くまで待つリスナー。 */
  // @ApplicationModuleListener のトランザクションの proxy を作るため final にしない。受信メソッドは on と命名する規約のため。
  @SuppressWarnings("PMD.ShortMethodName")
  /* package */ static class ProbeListener {

    /** 呼び出しの回数。 */
    private final AtomicInteger callCount = new AtomicInteger();

    /** 最後まで終えた呼び出しの回数。 */
    private final AtomicInteger finishedCount = new AtomicInteger();

    /** 残りの失敗の回数。 */
    private final AtomicInteger remainingFailures = new AtomicInteger();

    /** 閉じた門。開くまで呼び出しを待たせる。 */
    private final AtomicReference<@Nullable CountDownLatch> gate = new AtomicReference<>();

    // 門で待つ間に割り込まれたら、割り込みの状態を戻して失敗させる。
    @SuppressWarnings("PMD.DoNotUseThreads")
    @ApplicationModuleListener
    @ResubmissionPolicy(PROBE_POLICY)
    /* package */ void on(final ResubmissionProbe event) {
      callCount.incrementAndGet();
      try {
        final @Nullable CountDownLatch latch = gate.get();
        if (latch != null && !latch.await(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          throw new IllegalStateException("gate was not opened: id=" + event.id());
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted: id=" + event.id(), exception);
      }
      try {
        if (remainingFailures.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
          throw new IllegalStateException("probe listener is configured to fail: id=" + event.id());
        }
      } finally {
        finishedCount.incrementAndGet();
      }
    }

    /* package */ void reset() {
      callCount.set(0);
      finishedCount.set(0);
      remainingFailures.set(0);
      open();
    }

    /** 門を開け、待っている呼び出しを進める。 */
    /* package */ void open() {
      final @Nullable CountDownLatch latch = gate.getAndSet(null);
      if (latch != null) {
        latch.countDown();
      }
    }

    /* package */ void failTimes(final int times) {
      remainingFailures.set(times);
    }

    /* package */ int failuresLeft() {
      return remainingFailures.get();
    }

    /** 門を閉じ、開けるための latch を返す。 */
    /* package */ CountDownLatch close() {
      final CountDownLatch latch = new CountDownLatch(1);
      gate.set(latch);
      return latch;
    }

    /* package */ int calls() {
      return callCount.get();
    }

    /* package */ int finished() {
      return finishedCount.get();
    }
  }

  /** テストが時刻を進められる時計。値は常に {@link Clock#fixed} の値にする。 */
  /* package */ static final class MutableClock extends Clock {

    /** 今の固定の時計。 */
    private final AtomicReference<Clock> delegate =
        new AtomicReference<>(fixed(T0, ZoneOffset.UTC));

    /* package */ void set(final Instant instant) {
      delegate.set(fixed(instant, ZoneOffset.UTC));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return delegate.get().withZone(zone);
    }

    @Override
    public Instant instant() {
      return delegate.get().instant();
    }
  }

  /** 時計とテストのリスナーを登録する。リスナーは他のテストの component scan に入らないよう {@code @Bean} にする。 */
  @TestConfiguration(proxyBeanMethods = false)
  /* package */ static class ProbeConfiguration {

    @Bean
    @Primary
    /* package */ MutableClock mutableClock() {
      return new MutableClock();
    }

    @Bean
    /* package */ ProbeListener probeListener() {
      return new ProbeListener();
    }

    @Bean
    /* package */ OnceProbeListener onceProbeListener() {
      return new OnceProbeListener();
    }
  }
}
