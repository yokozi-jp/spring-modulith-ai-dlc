package com.example.demo.shared.infrastructure.persistence;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jooq.JooqLockProvider;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jooq.DSLContext;
import org.jooq.conf.MappedSchema;
import org.jooq.conf.RenderMapping;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

/**
 * 失敗したイベント出版の再投入の設定値、ロック、有効なときだけの定期の実行、手での 1 回の再投入の起動を構成する（ADR-077）。
 *
 * <p>ロックは ShedLock の jOOQ の provider で取る。provider はロックの期限を DB の時刻で書き込んで比べるため、インスタンスの時計がずれても排他が崩れない。
 * ロックの操作は 1 文ずつのトランザクションで終わり、接続を固定しないため、接続を多重化するプロキシ（RDS Proxy、PgBouncer のトランザクションモード）の後ろでも動く。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  EventPublicationResubmissionConfig.Properties.class,
  EventPublicationResubmissionConfig.ResubmitOnceProperties.class
})
class EventPublicationResubmissionConfig {

  /** 方針を指定しないリスナーの再投入の方針の名前。 */
  /* package */ static final String DEFAULT_POLICY = "default";

  /** ロックの表があるスキーマ。ShedLock の表の名前はスキーマを持たないため、SQL を組み立てるときに付ける。 */
  private static final String LOCK_SCHEMA = "modulith";

  /** 手での 1 回の再投入の対象の設定のキー。値があるときだけ、起動の後に 1 回だけ再投入して終了する。 */
  /* package */ static final String RESUBMIT_ONCE_TARGET = "event-publication.resubmit-once.target";

  /**
   * ロックの provider。ロックの表は {@code modulith.shedlock} にする。
   *
   * <p>ロックの操作は、呼び出し元のトランザクションに入らないよう、新しいトランザクション（{@code REQUIRES_NEW}）で実行する。
   * 呼び出し元のトランザクションに入ると、ロックの行がそのトランザクションの終わりまでコミットされず、他のインスタンスに見えないためである（ShedLock 7.9 の変更）。
   */
  @Bean
  /* package */ LockProvider eventPublicationLockProvider(
      final DSLContext dsl, final PlatformTransactionManager transactionManager) {
    final TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new JooqLockProvider(
        lockDsl(dsl),
        (lockDsl, callable) -> requiresNew.execute(status -> lockDsl.transactionResult(callable)));
  }

  /** 定期の再投入と手での 1 回の再投入が共有するロックの実行。 */
  @Bean
  /* package */ LockingTaskExecutor eventPublicationLockingTaskExecutor(
      final LockProvider eventPublicationLockProvider) {
    return new DefaultLockingTaskExecutor(eventPublicationLockProvider);
  }

  /**
   * Spring Boot の jOOQ の構成（ADR-070 の ExecuteListener、例外の変換）を保ち、スキーマのない ShedLock の表を {@code
   * modulith} に描画する。
   *
   * <p>search_path（HikariCP の {@code schema}）はセッションの設定で、接続を多重化するプロキシの後ろでは保たれないため、頼らない。
   */
  /* package */ static DSLContext lockDsl(final DSLContext dsl) {
    return dsl.configuration()
        .deriveSettings(
            settings ->
                settings.withRenderMapping(
                    new RenderMapping()
                        .withSchemata(new MappedSchema().withInput("").withOutput(LOCK_SCHEMA))))
        .dsl();
  }

  /**
   * 手での 1 回の再投入が求められているか。Runner の生成と、Web と定期の実行の停止は、この述語だけで決める。
   *
   * <p>対象が空白なら通常の起動である。空白以外の値は、{@code false} を含めて手での再投入の求めとして扱う。 不正な値は Runner が {@link
   * IllegalArgumentException} で受けて終了コード 1 にするため、何もせずに起動する状態を作らない。
   */
  /* package */ static boolean isResubmitOnceRequested(final PropertyResolver environment) {
    return !environment.getProperty(RESUBMIT_ONCE_TARGET, "").isBlank();
  }

  /** {@link #isResubmitOnceRequested} を {@code @Conditional} から呼ぶための条件。 */
  /* package */ static class ResubmitOnceRequested implements Condition {

    @Override
    public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
      return isResubmitOnceRequested(context.getEnvironment());
    }
  }

  /** 手での 1 回の再投入が求められているときだけ、起動の後に 1 回だけ再投入し、終了コードを返して JVM を終える。 */
  @Bean
  @Conditional(ResubmitOnceRequested.class)
  /* package */ ApplicationRunner resubmitOnceRunner(
      final EventPublicationResubmitOnce job,
      final ResubmitOnceProperties properties,
      final ConfigurableApplicationContext context) {
    return args -> {
      final int exitCode = resubmitOnceExitCode(job, properties.target());
      exit(SpringApplication.exit(context, () -> exitCode));
    };
  }

  /** 全件を完了したら 0、それ以外と例外では 1 を返す。 */
  // 運用者が起動した 1 回の実行の失敗を、例外の種類によらず終了コードで返すため、すべての実行時の例外を受ける。
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  /* package */ static int resubmitOnceExitCode(
      final EventPublicationResubmitOnce job, final @Nullable String target) {
    try {
      return job.run(target == null ? "" : target).succeeded() ? 0 : 1;
    } catch (RuntimeException e) {
      log.atError().setCause(e).log("Event publication resubmit-once failed");
      return 1;
    }
  }

  // 1 回の実行の後は、Redis や OTLP の送信のスレッドが残っても終わるよう、JVM を明示して終える。
  @SuppressWarnings("PMD.DoNotTerminateVM")
  private static void exit(final int exitCode) {
    System.exit(exitCode);
  }

  /** 有効で、手での 1 回の再投入でないときだけ、@Scheduled と Staleness Monitor を動かす。 */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnBooleanProperty("event-publication.resubmission.enabled")
  /* package */ static class Scheduling {}

  /**
   * 手での 1 回の再投入の対象があるとき、Web のサーバーと定期の実行（Staleness Monitor を含む）を止める。
   *
   * <p>同じイメージを 1 回だけ起動する入口であり、対象の設定 1 つで、HTTP の待ち受けと定期の再投入を動かさないようにする。 環境変数の値より優先するため、プロパティの先頭に足す。
   */
  /* package */ static class ResubmitOnceEnvironment implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(
        final ConfigurableEnvironment environment, final SpringApplication application) {
      if (!isResubmitOnceRequested(environment)) {
        return;
      }
      environment
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "resubmitOnce",
                  Map.of(
                      "spring.main.web-application-type",
                      "none",
                      "event-publication.resubmission.enabled",
                      "false")));
    }
  }

  /**
   * 再投入の設定値。
   *
   * @param enabled 定期の実行と Staleness Monitor を動かすか
   * @param waitAge 最初の失敗から 1 回目の再投入までの待ち時間で、2 回目からの待ち時間の基準。Staleness Monitor
   *     の閾値にも使う。リスナーの処理時間より十分に長くする
   * @param interval 前の回の終わりから次の回までの間隔
   * @param maxPerRun 1 回の実行で再投入する件数と、新しく記録する ERROR の件数の上限
   * @param lockAtMostFor ロックを持つ上限。実行が落ちて解放できなかったとき、この時間を過ぎると他のインスタンスが取れる。1 回の実行の最長の時間より十分に長くする
   * @param policies リスナーの再投入の方針。名前は {@code @ResubmissionPolicy} の値で、{@code default} は必須
   */
  /* package */ @ConfigurationProperties("event-publication.resubmission")
  @Validated
  record Properties(
      boolean enabled,
      @NotNull @DurationMin(seconds = 1) Duration waitAge,
      @NotNull @DurationMin(seconds = 1) Duration interval,
      @Min(1) int maxPerRun,
      @NotNull @DurationMin(seconds = 1) Duration lockAtMostFor,
      @NotEmpty Map<String, @Valid @NotNull Policy> policies) {

    /**
     * ロックを持つ下限。定期の実行の間隔の半分にする。
     *
     * <p>各インスタンスは自分の前の回の終わりから間隔を空けて動くため、N 台でも、ロックが下限まで残る間に来た他の台の回は省かれ、クラスタ全体の実行は間隔あたりおよそ 1 回から 2
     * 回になる。下限を間隔と同じにすると、1 台のときも、DB の時刻とインスタンスの時刻の測り方の差で自分の次の回を省きうるため、半分にして余裕を残す。
     */
    /* package */ Duration lockAtLeastFor() {
      return interval.dividedBy(2);
    }

    /** {@code default} の方針があるか。 */
    @AssertTrue(message = "event-publication.resubmission.policies.default is required")
    /* package */ boolean isDefaultPolicyDefined() {
      return policies != null && policies.containsKey(DEFAULT_POLICY);
    }

    /** ロックを持つ上限が間隔より長いか。短いと、ロックを持つ下限（間隔の半分）が上限を超えうる。 */
    @AssertTrue(
        message = "event-publication.resubmission.lock-at-most-for must be longer than interval")
    /* package */ boolean isLockAtMostForLongerThanInterval() {
      return lockAtMostFor == null || interval == null || lockAtMostFor.compareTo(interval) > 0;
    }

    /** どの方針の待ち時間の上限も、最初の待ち時間以上か。 */
    @AssertTrue(
        message =
            "event-publication.resubmission.policies.*.max-wait must not be shorter than wait-age")
    /* package */ boolean isMaxWaitNotShorterThanWaitAge() {
      return waitAge == null
          || policies == null
          || policies.values().stream()
              .allMatch(
                  policy ->
                      policy == null
                          || policy.maxWait() == null
                          || policy.maxWait().compareTo(waitAge) >= 0);
    }
  }

  /**
   * リスナーの再投入の方針。n 回目の再投入の前の待ち時間は、最後の試行から {@code min(wait-age × multiplier^(n-1), max-wait)} である。
   *
   * @param maxResubmissions 自動で再投入する回数の上限
   * @param multiplier 再投入のたびに待ち時間に掛ける倍率。1 なら待ち時間を延ばさない
   * @param maxWait 待ち時間の上限
   */
  /* package */ record Policy(
      @Min(1) int maxResubmissions,
      @DecimalMin("1.0") double multiplier,
      @NotNull @DurationMin(seconds = 1) Duration maxWait) {

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
   * 手での 1 回の再投入の設定値。
   *
   * @param target 再投入する出版。{@code all}、{@code policy:<方針の名前>}、{@code event-type:<イベントの完全修飾名>}
   *     のいずれか。起動の引数か環境変数で渡し、{@code application.yaml} には書かない
   * @param completionTimeout 再投入した出版の処理が終わるのを待つ上限
   */
  /* package */ @ConfigurationProperties("event-publication.resubmit-once")
  @Validated
  record ResubmitOnceProperties(
      @Nullable String target, @NotNull @DurationMin(seconds = 1) Duration completionTimeout) {}
}
