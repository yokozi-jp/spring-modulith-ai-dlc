package com.example.demo.testkit.idempotency;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 閉じている間に届いた、決めたリスナーの配信を、リスナーのトランザクションのコミットの直前（{@link
 * TransactionSynchronization#beforeCommit}）で止める門。業務の書き込みとロックは済んでいて、まだコミットしていない時点である。
 *
 * <p>{@code @ApplicationModuleListener} のメソッドを囲み、リスナーのトランザクションに同期を登録する。トランザクションの advisor
 * より内側で動く必要があり、{@link Ordered#LOWEST_PRECEDENCE} にする。同じ順位では、Spring の Bean の advisor（トランザクション）が
 * {@code @Aspect} の advisor より先（外側）に並ぶ。内側で動かなかったときは止めずに数え、検査が {@link
 * Violation.Kind#GATE_NOT_REACHED} にする。
 *
 * <p>止めるのは、テストが通すか、{@link #HOLD_LIMIT} を過ぎるまでである。過ぎたら例外でロールバックさせる。上限は、止めたトランザクションを {@code
 * idle_in_transaction_session_timeout}（テストは 10 秒）が切るより短くする。
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
/* package */ final class CommitGate {

  /** 1 回の配信を止める上限。 */
  /* package */ static final Duration HOLD_LIMIT = Duration.ofSeconds(5);

  /** 閉じている門。開いていれば {@code null}。 */
  private final AtomicReference<@Nullable Hold> closed = new AtomicReference<>();

  /** 門を閉じ、そのリスナーの配信を、届くたびにコミットの直前で止める。閉じたままの門は開けてから閉じ直す。 */
  /* package */ Hold close(final ListenerId listener) {
    final Hold hold = new Hold(listener, closed);
    final @Nullable Hold previous = closed.getAndSet(hold);
    if (previous != null) {
      previous.open();
    }
    return hold;
  }

  // 対象のリスナーが投げた例外を記録して投げ直すだけで、握りつぶさない。
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @Around("@annotation(org.springframework.modulith.events.ApplicationModuleListener)")
  /* package */ @Nullable Object hold(final ProceedingJoinPoint joinPoint) throws Throwable {
    final @Nullable Hold hold = closed.get();
    final Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
    if (hold == null || !hold.listener.equals(ListenerId.forMethod(method))) {
      return joinPoint.proceed();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void beforeCommit(final boolean readOnly) {
              hold.arriveAndWait();
            }
          });
    } else {
      hold.outsideCount.incrementAndGet();
    }
    try {
      return joinPoint.proceed();
    } catch (Throwable e) {
      hold.thrown.add(e);
      throw e;
    }
  }

  /** 閉じた門の 1 回分。開けると（{@link #open()}）、止めている配信をすべて通す。 */
  /* package */ static final class Hold {

    /** 止める対象のリスナー。 */
    private final ListenerId listener;

    /** 門の状態。閉じたら自分を外す。 */
    private final AtomicReference<@Nullable Hold> gate;

    /** 届いた配信を止めている latch。届いた順に並ぶ。 */
    private final List<CountDownLatch> latches = new CopyOnWriteArrayList<>();

    /** 届いた配信のうち、通した数。 */
    private final AtomicInteger released = new AtomicInteger();

    /** トランザクションの外で対象のリスナーを囲んだ回数。 */
    private final AtomicInteger outsideCount = new AtomicInteger();

    /** 対象のリスナーが投げた例外。 */
    private final Queue<Throwable> thrown = new ConcurrentLinkedQueue<>();

    /** 開けたか。開けた後に届いた配信は止めない。 */
    private final AtomicBoolean opened = new AtomicBoolean();

    private Hold(final ListenerId listener, final AtomicReference<@Nullable Hold> gate) {
      this.listener = listener;
      this.gate = gate;
    }

    /** コミットの直前に届いた配信の数。 */
    /* package */ int arrivals() {
      return latches.size();
    }

    /** 止めている配信のうち、最も早く届いたものを通す。 */
    /* package */ void releaseNext() {
      final int next = released.getAndIncrement();
      if (next < latches.size()) {
        latches.get(next).countDown();
      }
    }

    /** トランザクションの外で対象のリスナーを囲んだ回数。0 でなければ、門は配信を止められない。 */
    /* package */ int outsideTransaction() {
      return outsideCount.get();
    }

    /** 対象のリスナーが投げた例外の要約。 */
    /* package */ List<String> failures() {
      return thrown.stream().map(Throwable::toString).toList();
    }

    /** 止めている配信をすべて通し、門を開ける。 */
    /* package */ void open() {
      opened.set(true);
      latches.forEach(CountDownLatch::countDown);
      gate.compareAndSet(this, null);
    }

    // 止めている間に割り込まれたら、割り込みの状態を戻してロールバックさせる。
    @SuppressWarnings("PMD.DoNotUseThreads")
    private void arriveAndWait() {
      final CountDownLatch latch = new CountDownLatch(1);
      latches.add(latch);
      // 開けた後に届いた配信は、open が latch を見なかったかもしれないので自分で通す。
      if (opened.get()) {
        latch.countDown();
      }
      try {
        if (!latch.await(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException(
              "commit gate was not opened within " + HOLD_LIMIT + ": " + listener);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted at the commit gate: " + listener, e);
      }
    }
  }
}
