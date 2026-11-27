package com.example.demo.shared.eventresubmission;

import com.example.demo.shared.locking.PeriodicLockTiming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.validation.annotation.Validated;

/**
 * 失敗したイベント出版の再投入の設定値と、有効なときだけの定期の実行を構成する（ADR-079）。
 *
 * <p>ロックの構成は {@code com.example.demo.shared.locking.LockConfig} に置く。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  EventPublicationResubmissionConfig.Properties.class,
  EventPublicationResubmissionConfig.AbandonProperties.class,
  EventPublicationResubmissionConfig.StalenessMonitorProperties.class
})
class EventPublicationResubmissionConfig {

  /** 方針を指定しないリスナーの再投入の方針の名前。 */
  /* package */ static final String DEFAULT_POLICY = "default";

  /**
   * 有効で、手での 1 回の再投入でないときだけ、@Scheduled の定期の再投入（古い出版を FAILED に戻す処理を含む）を動かす。
   *
   * <p>有効かどうかを決めるのはこの条件だけで、{@link Properties} には持たない。 Boot の束ね方は {@code yes} や {@code 1}
   * も真と読むが、この条件は {@code true}（大文字小文字を問わない）だけを真とするためである。
   *
   * <p>{@code @EnableScheduling} はアプリのすべての {@code @Scheduled} を動かし、ほかの定期のジョブ（{@code
   * IdempotencyKeyPurgeConfig}）も付けるため、{@code @Scheduled} はこの条件付きの構成に置き、{@link
   * EventPublicationResubmitter} には置かない。
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnBooleanProperty("event-publication.resubmission.enabled")
  /* package */ static class Scheduling {

    /** 失敗した出版を再投入するジョブ。 */
    private final EventPublicationResubmitter resubmitter;

    /* package */ Scheduling(final EventPublicationResubmitter resubmitter) {
      this.resubmitter = resubmitter;
    }

    /** 前の回の終わりから {@code interval} を空けて、再投入を 1 回動かす。 */
    @Scheduled(
        fixedDelayString = "${event-publication.resubmission.interval}",
        initialDelayString = "${event-publication.resubmission.interval}")
    /* package */ void resubmitOnce() {
      resubmitter.resubmitOnce();
    }
  }

  /**
   * 再投入の設定値。
   *
   * @param waitAge 最初の失敗から 1 回目の再投入までの待ち時間で、2 回目からの待ち時間の基準。PUBLISHED の出版を古いとして FAILED
   *     に戻す閾値にも使う。リスナーの処理時間より十分に長くする
   * @param inFlightStaleness PROCESSING と RESUBMITTED の出版を古いとして FAILED
   *     に戻す閾値。最後の出版か再投入の時刻から測る。{@code lock-at-most-for} の 2 倍より長くする
   * @param interval 前の回の終わりから次の回までの間隔
   * @param maxPerRun 1 回の実行で再投入する件数の上限
   * @param lockAtMostFor ロックを持つ上限。実行が落ちて解放できなかったとき、この時間を過ぎると他のインスタンスが取れる。1 回の実行の最長の時間より十分に長くする
   * @param policies リスナーの再投入の方針。名前は {@code @ResubmissionPolicy} の値で、{@code default} は必須
   */
  /* package */ @ConfigurationProperties("event-publication.resubmission")
  @Validated
  record Properties(
      @NotNull @DurationMin(seconds = 1) Duration waitAge,
      @NotNull @DurationMin(seconds = 1) Duration inFlightStaleness,
      @NotNull @DurationMin(seconds = 1) Duration interval,
      @Min(1) int maxPerRun,
      @NotNull @DurationMin(seconds = 1) Duration lockAtMostFor,
      @NotEmpty Map<String, @Valid @NotNull Policy> policies)
      implements PeriodicLockTiming {

    /**
     * 再投入した出版の結果を読み戻すために覚えておく期間。{@code max(3 × lock-at-most-for, in-flight-staleness +
     * lock-at-most-for + 2 × interval)}。
     *
     * <p>落ちたまま残った再投入の出版の last_resubmission_date は、この回の時刻から最大で {@code lock-at-most-for} 遅れる。
     * 定期のジョブはロックを取った回に、そこから {@code in-flight-staleness} を過ぎた出版を FAILED に戻し、ロックはどれかのインスタンスがおよそ
     * {@code interval} ごとに取る。 次の回はさらに最大で {@code interval} 後に数える。 その後の回で数えるまでの余裕として、ロックの上限の 3
     * 倍とも比べて長い方を取る。
     */
    /* package */ Duration resubmissionLifetime() {
      final Duration byLock = lockAtMostFor.multipliedBy(3);
      final Duration byStaleness =
          inFlightStaleness.plus(lockAtMostFor).plus(interval.multipliedBy(2));
      return byLock.compareTo(byStaleness) > 0 ? byLock : byStaleness;
    }

    /** 1 回の実行で、再投入の候補を選び続けてよい時間。ロックの上限の半分で、超えた後の行は次の回に回す。 */
    /* package */ Duration runBudget() {
      return lockAtMostFor.dividedBy(2);
    }

    /** {@code default} の方針があるか。 */
    @AssertTrue(message = "event-publication.resubmission.policies.default is required")
    /* package */ boolean isDefaultPolicyDefined() {
      return policies != null && policies.containsKey(DEFAULT_POLICY);
    }

    /** どの方針の待ち時間の上限も、最初の待ち時間以上か。 */
    @AssertTrue(
        message =
            "event-publication.resubmission.policies.*.max-wait must not be shorter than wait-age")
    /* package */ boolean isMaxWaitNotShorterThanWaitAge() {
      return waitAge == null
          || policies == null
          || policies.values().stream()
              .allMatch(policy ->
                  policy == null
                      || policy.maxWait() == null
                      || policy.maxWait().compareTo(waitAge) >= 0);
    }

    /**
     * PROCESSING と RESUBMITTED の出版を FAILED に戻す閾値が、ロックを持つ上限の 2 倍より長いか（ADR-079）。
     *
     * <p>古い出版を FAILED に戻す処理はロックの中で動くが、リスナーの処理は、再投入の時刻から測って {@code lock-at-most-for}
     * まで、ロックを放した後も続きうる。 2 倍を超えるまでは処理中でありうる。
     */
    @AssertTrue(
        message =
            "event-publication.resubmission.in-flight-staleness must be longer than twice"
                + " lock-at-most-for")
    /* package */ boolean isInFlightStalenessLongerThanTwiceLockAtMostFor() {
      return inFlightStaleness == null
          || lockAtMostFor == null
          || inFlightStaleness.compareTo(lockAtMostFor.multipliedBy(2)) > 0;
    }
  }

  /**
   * リスナーの再投入の方針。n 回目の再投入の前の待ち時間は、最後の試行から {@code min(wait-age × multiplier^(n-1), max-wait)} である。
   *
   * @param multiplier 再投入のたびに待ち時間に掛ける倍率。1 なら待ち時間を延ばさない
   * @param maxWait 待ち時間の上限
   * @param abandonAfterAttempts このリスナーの出版を諦める回数。全体の {@code abandon-after-attempts} と同じ数え方で、1
   *     は最初の配信の失敗で諦める（自動で再投入しない）。全体の値より小さくする。{@code null} なら全体の値に従う
   */
  /* package */ record Policy(
      @DecimalMin("1.0") double multiplier,
      @NotNull @DurationMin(seconds = 1) Duration maxWait,
      @Nullable @Min(1) Integer abandonAfterAttempts) {

    /**
     * 次の再投入の前の待ち時間。
     *
     * @param waitAge 最初の待ち時間
     * @param completionAttempts これまでの試行の回数（再投入した回数 + 1）。次の再投入はその回数番目の再投入である
     */
    /* package */ Duration waitBefore(final Duration waitAge, final int completionAttempts) {
      final double factor = Math.pow(multiplier, Math.max(0, completionAttempts - 1));
      // 倍率を掛けた待ち時間がナノ秒の long を超えないよう、上限との比で先に比べる。
      if (factor >= (double) maxWait.toNanos() / waitAge.toNanos()) {
        return maxWait;
      }
      return Duration.ofNanos(Math.round(waitAge.toNanos() * factor));
    }
  }

  /**
   * Spring Modulith の {@code spring.modulith.events.resubmission} のうち、再投入の回数の上限（ADR-079）。
   *
   * <p>Spring Modulith は、失敗した試行の前に読んだ {@code completion_attempts} がこの値以上なら、出版を {@code ABANDONED}
   * にする。 最初の配信と 1 回目の再投入の失敗はどちらも 1 と比べる（ResubmissionOptionsLearningTest）ため、2 以上なら
   * この値の回数目の再投入の失敗で諦め、1 なら最初の配信の失敗で諦める。 1 と未設定（Spring Modulith は諦めない）は起動に失敗させる。
   *
   * @param abandonAfterAttempts 自動で再投入する回数の上限。方針の {@code abandon-after-attempts} がなければ、全リスナーの既定になる
   */
  /* package */ @ConfigurationProperties("spring.modulith.events.resubmission")
  @Validated
  record AbandonProperties(
      @Min(
              value = 2,
              message =
                  "spring.modulith.events.resubmission.abandon-after-attempts must be at least 2:"
                      + " 1 abandons the first delivery")
          int abandonAfterAttempts) {}

  /**
   * Spring Modulith の {@code spring.modulith.events.staleness} のうち、組み込みの Staleness Monitor の閾値。
   *
   * <p>どれかを書くと、Spring Modulith 2.2.0 は組み込みの Staleness Monitor を定期のタスクに登録し、古い出版をロックの外で FAILED に戻す。
   * 古い出版を FAILED に戻す処理はロックの中で呼ぶため（{@link FailedPublicationSweep}、ADR-079）、書いたら起動に失敗させる。 {@code
   * check-interval} だけではタスクを登録しないため、見ない。
   *
   * @param published {@code PUBLISHED} の閾値
   * @param processing {@code PROCESSING} の閾値
   * @param resubmitted {@code RESUBMITTED} の閾値
   */
  /* package */ @ConfigurationProperties("spring.modulith.events.staleness")
  @Validated
  record StalenessMonitorProperties(
      @Nullable Duration published, @Nullable Duration processing, @Nullable Duration resubmitted) {

    /** 組み込みの Staleness Monitor の閾値をどれも書いていないか。 */
    @AssertTrue(
        message =
            "spring.modulith.events.staleness.published, processing and resubmitted must not be set:"
                + " stale publications are marked failed inside the resubmission lock")
    /* package */ boolean isBuiltInMonitorOff() {
      return published == null && processing == null && resubmitted == null;
    }
  }
}
