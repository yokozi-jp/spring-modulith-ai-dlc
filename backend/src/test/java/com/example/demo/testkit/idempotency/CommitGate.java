package com.example.demo.testkit.idempotency;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
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
 * {@code @Aspect} の advisor より先（外側）に並ぶ。内側で動かなかったときは止めずに数え、検査が {@link Violation.Kind#FIRST_NOT_HELD}
 * にする。
 *
 * <p>止めた配信は、届いた順に 1 つずつ通す（公平な {@link Semaphore}）。通す許可が届く前に出ていれば、届いた配信は待たずに通る。止めるのは、テストが通すか、{@link
 * #HOLD_LIMIT} を過ぎるまでである。過ぎたら例外でロールバックさせる。上限は、止めたトランザクションを {@code
 * idle_in_transaction_session_timeout}（テストは 10 秒）が切るより短くする。
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
/* package */ final class CommitGate {

  /** 1 回の配信を止める上限。 */
  /* package */ static final Duration HOLD_LIMIT = Duration.ofSeconds(5);

  /** 止めた配信のセッションの ID を読む jOOQ のコンテキスト。リスナーのトランザクションの接続で読む。 */
  private final DSLContext dsl;

  /** 閉じている門。開いていれば {@code null}。 */
  private final AtomicReference<@Nullable Hold> closed = new AtomicReference<>();

  /* package */ CommitGate(final DSLContext dsl) {
    this.dsl = dsl;
  }

  /** 門を閉じ、そのリスナーの配信を、届くたびにコミットの直前で止める。閉じたままの門は開けてから閉じ直す。 */
  /* package */ Hold close(final ListenerId listener) {
    final Hold hold = new Hold(listener);
    final @Nullable Hold previous = closed.getAndSet(hold);
    if (previous != null) {
      previous.open();
    }
    return hold;
  }

  /** 止めている配信をすべて通し、門を開ける。開けた後に届いた配信は止めない。 */
  /* package */ void open() {
    final @Nullable Hold hold = closed.getAndSet(null);
    if (hold != null) {
      hold.open();
    }
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
              hold.arriveAndWait(backendPid());
            }
          });
    } else {
      hold.outside.incrementAndGet();
    }
    try {
      return joinPoint.proceed();
    } catch (Throwable e) {
      hold.thrown.add(e);
      throw e;
    }
  }

  /** 今のトランザクションの接続の、PostgreSQL のセッションの ID。 */
  private int backendPid() {
    return dsl.select(DSL.function(DSL.name("pg_backend_pid"), Integer.class))
        .fetchSingle()
        .value1();
  }

  /** 閉じた門の 1 回分。 */
  /* package */ static final class Hold {

    /** 止める対象のリスナー。 */
    private final ListenerId listener;

    /** 止めた配信を通す許可。届いた順に通すため公平にする。 */
    private final Semaphore commits = new Semaphore(0, true);

    /** コミットの直前に届いた配信のセッションの ID。届いた順に並ぶ。 */
    private final Queue<Integer> arrivedPids = new ConcurrentLinkedQueue<>();

    /** トランザクションの外で対象のリスナーを囲んだ回数。 */
    private final AtomicInteger outside = new AtomicInteger();

    /** 対象のリスナーが投げた例外。 */
    private final Queue<Throwable> thrown = new ConcurrentLinkedQueue<>();

    private Hold(final ListenerId listener) {
      this.listener = listener;
    }

    /** コミットの直前に届いた配信の数。 */
    /* package */ int arrivals() {
      return arrivedPids.size();
    }

    /**
     * 最初にコミットの直前に届いた配信のセッションの ID。
     *
     * @throws IllegalStateException まだ届いた配信がない場合
     */
    /* package */ int firstPid() {
      final @Nullable Integer pid = arrivedPids.peek();
      if (pid == null) {
        throw new IllegalStateException("no delivery has reached the commit gate: " + listener);
      }
      return pid;
    }

    /** 止めている配信のうち、最も早く届いたものを通す。まだ届いていなければ、次に届く配信を通す。 */
    /* package */ void releaseNext() {
      commits.release();
    }

    /** トランザクションの外で対象のリスナーを囲んだ回数。0 でなければ、門は配信を止められない。 */
    /* package */ int outsideTransaction() {
      return outside.get();
    }

    /** 対象のリスナーが投げた例外の要約。 */
    /* package */ List<String> failures() {
      return thrown.stream().map(Throwable::toString).toList();
    }

    /** 止めている配信と、これから届く配信をすべて通す。許可を上限まで足し、何度呼んでも溢れさせない。 */
    private void open() {
      commits.release(Integer.MAX_VALUE - commits.availablePermits());
    }

    // 止めている間に割り込まれたら、割り込みの状態を戻してロールバックさせる。
    @SuppressWarnings("PMD.DoNotUseThreads")
    private void arriveAndWait(final int pid) {
      arrivedPids.add(pid);
      try {
        if (!commits.tryAcquire(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
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
