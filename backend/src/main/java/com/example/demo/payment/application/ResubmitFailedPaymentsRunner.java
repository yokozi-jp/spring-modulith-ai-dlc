package com.example.demo.payment.application;

import com.example.demo.ordering.OrderConfirmed;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.stereotype.Component;

/**
 * 失敗した OrderConfirmed のイベント出版を一度だけ再投入する入口。
 *
 * <p>resubmit-once のプロファイルのときだけ作る。起動のたびに 1 回だけ動き、定期には動かない。
 */
// 運用者の入口と定期の再投入（#108）が入るまでの再投入の入口で、docs/backend/class-roles/index.md にない役割である。
@Slf4j
@Component
@Profile("resubmit-once")
class ResubmitFailedPaymentsRunner implements ApplicationRunner {

  /** 一度に読む失敗した出版の上限。 */
  /* package */ static final int BATCH_SIZE = 100;

  /** Spring Modulith の失敗した出版の再投入。 */
  private final FailedEventPublications failedEventPublications;

  /** Spring Modulith の失敗した出版の再投入を受け取る。 */
  /* package */ ResubmitFailedPaymentsRunner(
      final FailedEventPublications failedEventPublications) {
    this.failedEventPublications = failedEventPublications;
  }

  /** 失敗した出版のうち OrderConfirmed だけを再投入し、対象に選んだ件数を INFO で記録する。 */
  @Override
  public void run(final ApplicationArguments args) {
    final AtomicInteger matched = new AtomicInteger();
    // ponytail: LIMIT（BATCH_SIZE）の後に filter が掛かるため、OrderConfirmed 以外の失敗した出版が 100 件を超えると
    // その起動では OrderConfirmed に届かない。そのときは起動し直すか、#108 の定期の再投入に替える。
    failedEventPublications.resubmit(
        ResubmissionOptions.defaults()
            .withFilter(
                publication -> {
                  final boolean target = publication.getEvent() instanceof OrderConfirmed;
                  if (target) {
                    matched.incrementAndGet();
                  }
                  return target;
                })
            .withBatchSize(BATCH_SIZE));
    final int count = matched.get();
    log.info("Resubmitted failed event publications: eventType=OrderConfirmed, matched={}", count);
  }
}
