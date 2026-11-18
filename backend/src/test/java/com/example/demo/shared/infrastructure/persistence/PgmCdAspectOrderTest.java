package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** {@link PgmCdAspect} の束縛が、CommandHandler と Listener のトランザクションより外側で効くことを検証する（ADR-051）。 */
// ApplicationContextRunner の run に渡す callback の中で assert するため、PMD はテストメソッドの assert を検出できない。
// fixture の CommandHandler と Listener は、pointcut の対象にするためメソッドを public にする。
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.PublicMemberInNonPublicType"})
class PgmCdAspectOrderTest {

  @Test
  @DisplayName("CommandHandler のトランザクションが始まる時点と確定する時点で、pgm_cd が束縛されている")
  void pgmCdIsBoundWhileCommandHandlerTransactionBeginsAndCommits() {
    contextRunner()
        .run(context -> {
          context.getBean(ShipOrderCommandHandler.class).handle();

          final RecordingTransactionManager transactionManager =
              context.getBean(RecordingTransactionManager.class);
          assertThat(transactionManager.pgmCdsAtBegin)
              .as("getTransaction の時点の pgm_cd")
              .containsExactly("shared.ShipOrder");
          assertThat(transactionManager.pgmCdsAtCommit)
              .as("commit の時点の pgm_cd")
              .containsExactly("shared.ShipOrder");
        });
  }

  @Test
  @DisplayName("Listener のトランザクションが始まる時点と確定する時点で、pgm_cd が束縛されている")
  void pgmCdIsBoundWhileListenerTransactionBeginsAndCommits() {
    contextRunner()
        .run(context -> {
          context.getBean(OrderShippedListener.class).on();

          final RecordingTransactionManager transactionManager =
              context.getBean(RecordingTransactionManager.class);
          assertThat(transactionManager.pgmCdsAtBegin)
              .as("getTransaction の時点の pgm_cd")
              .containsExactly("shared.OrderShipped");
          assertThat(transactionManager.pgmCdsAtCommit)
              .as("commit の時点の pgm_cd")
              .containsExactly("shared.OrderShipped");
        });
  }

  private static ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(AopAutoConfiguration.class, TransactionAutoConfiguration.class))
        .withUserConfiguration(PgmCdAspect.class)
        .withBean(RecordingTransactionManager.class)
        .withBean(ShipOrderCommandHandler.class)
        .withBean(OrderShippedListener.class);
  }

  /** トランザクションを始めた時点と確定した時点の pgm_cd を記録するトランザクションマネージャー。 */
  /* package */ static final class RecordingTransactionManager
      implements PlatformTransactionManager {

    /** getTransaction のたびに記録した pgm_cd。束縛されていなければ {@code unbound}。 */
    private final List<String> pgmCdsAtBegin = new ArrayList<>();

    /** commit のたびに記録した pgm_cd。束縛されていなければ {@code unbound}。 */
    private final List<String> pgmCdsAtCommit = new ArrayList<>();

    @Override
    public TransactionStatus getTransaction(final @Nullable TransactionDefinition definition) {
      pgmCdsAtBegin.add(PgmCdAspect.PGM_CD.orElse("unbound"));
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(final TransactionStatus status) {
      pgmCdsAtCommit.add(PgmCdAspect.PGM_CD.orElse("unbound"));
    }

    @Override
    public void rollback(final TransactionStatus status) {
      // 記録だけが目的で、戻すものはない。
    }
  }

  /** pointcut とトランザクションの両方の対象になる CommandHandler。 */
  // CGLIB のプロキシを作れるよう、final にしない。
  /* package */ static class ShipOrderCommandHandler {

    /** トランザクションの中で何もしない。 */
    @Transactional
    public void handle() {
      // トランザクションの開始と確定の時点だけを確かめる。
    }
  }

  /** pointcut とトランザクションの両方の対象になる Listener。 */
  // CGLIB のプロキシを作れるよう、final にしない。
  /* package */ static class OrderShippedListener {

    /** トランザクションの中で何もしない。 */
    @Transactional
    @SuppressWarnings("PMD.ShortMethodName") // ADR-050 が Listener のメソッド名を on に決めている。
    public void on() {
      // トランザクションの開始と確定の時点だけを確かめる。
    }
  }
}
