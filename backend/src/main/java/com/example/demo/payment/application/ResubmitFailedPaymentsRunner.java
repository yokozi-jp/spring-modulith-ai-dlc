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
 *
 * <p>resubmit-once で動くインスタンスが 1 つだけであることを前提にする。複数のインスタンスが同時に動くと、同じ FAILED の出版を並行して再投入しうる。 そのとき二つ目の
 * Listener の実行は決済記録の一意制約に当たり、出版が FAILED に戻ることがある。決済代行への請求は冪等性キーで二重にならない。 定期の再投入（ADR-075 の
 * EventPublicationResubmitter）は advisory lock でインスタンスを 1 つに絞るが、この入口はロックを取らない。 重ねて動かしたくないときは、先に
 * EVENT_PUBLICATION_RESUBMISSION_ENABLED=false にする。
 * 定期の再投入の上限に達した出版を、運用者が原因を直したあとに手で再投入する入口として使う（docs/observability/runbook-event-publication-resubmission.md）。
 */
// 定期の再投入の上限に達した出版を手で再投入する運用者の入口で、docs/backend/class-roles/index.md にない役割である。
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
    // その起動では OrderConfirmed に届かない。そのときは起動し直すか、上限に達した出版を Runbook の手順で片付ける。
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
