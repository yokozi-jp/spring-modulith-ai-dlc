# 遅い外部呼び出しのリスナーと DB だけのリスナーが executor を取り合わない形（#557）

調べた日：2026-11-23。こちらの版は Spring Boot 4.1.2、Spring Framework 7.0.x、Spring Modulith 2.1.2（ADR-079）。最新は Spring Boot 4.2.0（2026-11-19 公開）。この調査の判断を変える項目は、確かめた範囲では見つからなかった。

## 問い

外部システムを呼ぶ遅いリスナーと、DB だけを書くリスナーが、1 つの executor の枠（`spring.task.execution.simple.concurrency-limit: 2`）を取り合わないようにするには、どの形を選ぶか。

## 結論の要約

一部採用。

- **採用：候補 1（外部システムを呼ぶリスナーに別の executor）。** 実測で、別の executor を持つと、DB だけのリスナーは遅いリスナーが枠を埋めていても 14 ms で動き始める（今は約 1.5 秒待つ）。ただし 3 つの条件を満たす。
  - Spring Boot の `spring.task.execution.mode=force` を設定する。設定しないと、別の `Executor` の bean を足した時点で Boot の `applicationTaskExecutor` が作られず、DB だけのリスナーまでが新しい executor で動く。
  - 注釈は、`@ApplicationModuleListener` を meta 注釈にし、`@AliasFor(annotation = Async.class, attribute = "value")` で executor の名前を渡す形で作る（実測で動く）。
  - `TenantListenerAspect` の pointcut（`@annotation(ApplicationModuleListener)` は meta 注釈を見ない）と ArchUnit の規則と終了の前に executor を閉じる処理を、新しい注釈に合わせる。合わせないと、テナントが束縛されずにトランザクションが開けない（実測）。
- **取り込まない：候補 3（別プロセス）。** #342 が決めた。
- **候補 2（決済代行の呼び出しを DB のトランザクションの外に出す）は、#581 が決める。** この調査で分かったことを #581 に渡す：外に出しても枠は待つ間も持たれたままで、DB だけのリスナーは待つ（実測）。接続の予算の問題と、枠の取り合いの問題は別である。
- **取り込まない：`@ConcurrencyLimit`（Spring Framework 7）。** 遅いリスナーの待ちが共有の executor の枠を持ち続けるので、DB だけのリスナーは待つ（実測）。
- **別の作業：出版した要求のスレッドが executor の空きを待つ問題。** 別の executor を足しても、満杯の executor に出版する要求は待つ（実測、3 つ目の遅いリスナーの出版が 1495 ms）。上限つきのキュー（Boot の pool のプロパティ）で待たなくなる（実測、7 ms）。

## 今の状態（実測）

`ListenerExecutorContentionExperimentTest`（このブランチの `research/listener-executor-isolation/`）。`applicationTaskExecutor` は `SimpleAsyncTaskExecutor`（仮想スレッド）、`concurrencyLimit=2`。遅いリスナーはトランザクションの中で 1500 ms 眠る。

| 場面                                                                                                          | 結果                                                                                                                                                           |
| ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 遅いリスナーを 2 つ動かす                                                                                     | Hikari の使用中の接続 2、PostgreSQL の状態は `idle in transaction` が 2                                                                                        |
| その間に DB だけのリスナーのイベントを出版する                                                                | 出版が戻るまで 1491 ms、リスナーが動き始めるまで 1494 ms（遅いリスナーの 1500 ms とほぼ同じ）                                                                  |
| 空きのない executor に出版する要求のスレッド                                                                  | `SimpleAsyncTaskExecutor.execute` → `ConcurrencyThrottleSupport.onLimitReached` で WAITING。待つ間も Hikari の使用中の接続が 1（要求のトランザクションの接続） |
| 候補 2 の形（`@ApplicationModuleListener(propagation = NOT_SUPPORTED)` で、外部の待ちはトランザクションの外） | 待つ間の使用中の接続 0（テストのスレッドで測った値）。ただし DB だけのリスナーが動き始めるまで 866〜884 ms（枠は持たれたまま）                                 |

3 回の実行で同じ傾向だった。

つまり、チケットの 2 つの害（ほかのリスナーが動き始められない、出版する要求のスレッドが待たされる）は、どちらも実測で再現した。今の main にリスナーは `OrderConfirmedListener` の 1 つだけで、取り合う相手のリスナーは、まだない。

## 候補ごとの結果

### 候補 1：別の executor

`ListenerExecutorIsolationExperimentTest`（`@ApplicationModuleTest(mode = ALL_DEPENDENCIES)`、`spring.task.execution.mode=force`）。`slowListenerExecutor` は `SimpleAsyncTaskExecutorBuilder` で作り、`concurrencyLimit(2)`。

| 実験                                                                                                                                                                                                                                       | 結果                                                                                                                                                                                                                                                |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@Async("x")`、`@Transactional(REQUIRES_NEW)`、`@TransactionalEventListener` を並べた注釈（V1）と、`@ApplicationModuleListener` を meta 注釈にして `@AliasFor(annotation = Async.class, attribute = "value")` で executor を渡す注釈（V2） | どちらのリスナーも `slow-listener-*` のスレッドで動いた                                                                                                                                                                                             |
| 専用の executor の 2 枠を遅いリスナーで埋め、DB だけのリスナーを出版                                                                                                                                                                       | 出版 7 ms、リスナーの開始 14 ms、スレッド `task-1`（既定の executor）。使用中の接続 3（遅い 2 と DB だけの 1）                                                                                                                                      |
| 同じ実験を `mode=force` なしで                                                                                                                                                                                                             | DB だけのリスナーが `slow-listener-3` で動き、開始まで 1762 ms                                                                                                                                                                                      |
| 専用の executor が満杯のとき、3 つ目の遅いリスナーを出版する要求                                                                                                                                                                           | 1495 ms 待つ                                                                                                                                                                                                                                        |
| 業務モジュールの package（`payment.application`）に V2 の注釈のリスナーを置く                                                                                                                                                              | 動かない。`IllegalStateException: tenant is not bound for the transaction ...ExperimentTenantComposedListener.on: start it from a request with a selected tenant or an @ApplicationModuleListener`（`CannotCreateTransactionException` に包まれる） |

分かったこと：

- **Boot の仕組み**：Spring Boot 4.1.2 の `TaskExecutorConfigurations.OnExecutorCondition` は、`@ConditionalOnMissingBean(Executor.class)` か `spring.task.execution.mode=force` のどちらかで `applicationTaskExecutor` を作る。別の `Executor` の bean があるだけで、`applicationTaskExecutor` と、`@Async` の既定をそれへ向ける `ApplicationTaskExecutorAsyncConfigurer` が消える。force は、Boot が用意した止め方である。
- **注釈の meta 化**：`@AliasFor(annotation = Async.class)` は、`@ApplicationModuleListener` の meta 注釈の `@Async` に届く。`ResubmissionPolicies` は `AnnotatedElementUtils.findMergedAnnotation` を使うので、`@ResubmissionPolicy` と再投入のリスナーの ID（メソッドから作る）は変わらない。
- **届かない所**：`TenantListenerAspect` の `@annotation(ApplicationModuleListener)`（AspectJ の `@annotation` は直接の注釈だけ）、`ClassRoleArchTest` の `areAnnotatedWith(ApplicationModuleListener.class)`（`eventListenersAreApplicationModuleListeners` の `areNotAnnotatedWith` を含む）、`ModuleRoleTransactionManager` のエラーメッセージ、`ResubmissionPolicies.listenerId` のエラーメッセージ。新しい注釈を足すなら、この 4 か所を直す。
- **終了の前の処理**：`EventPublicationResubmissionConfig` は `applicationTaskExecutor` だけを閉じて待つ（ADR-079）。executor を足したら、それも閉じて待つ。Boot の `ThreadPoolTaskExecutorBuilder` と `SimpleAsyncTaskExecutorBuilder` は `spring.task.execution.shutdown.*` を読むが、閉じる順は確かめていない。
- **上限**：`DemoApplicationTest` の「同時実行数は pool の半分以下」は、executor ごとの値の合計で確かめることになる。専用の executor は、DB を使う間は接続を持つ（候補 2 が採られれば、外部の待ちの間は持たない）。上限は、DB の接続の予算（pool の半分、`docs/integration/async-flow-control.md`）を 2 つの executor で分ける。外部の待ちが接続を持たなくなれば、専用の executor の上限は外部システムの許す同時実行数から決められる。

### 候補 2：決済代行の呼び出しを DB のトランザクションの外へ

候補 2 は #581（grilling）が決める。この調査で測れたのは、接続の側の効果と、枠への効果である（上の表の 4 行目）。外に出しても、待つ間の枠は持たれる。`docs/database/connections.md` と ADR-050、ADR-072、`formal-verify-order-payment` のモデルへの影響は #581 で確かめる。今回の executor の選択は、`ChargeOrderCommandHandler` の中の構成を変えないので、モデルの前提（出版、配信、決済の記録の順序）は変わらないと見ているが、モデルの実行は確かめていない。

### 候補 3：別プロセス

候補 3 は、#342 が決めた通り分けない。

### `@ConcurrencyLimit`（Spring Framework 7、ADR-019 の bulkhead の代わり）

`ConcurrencyLimitExperimentTest`。`@ApplicationModuleListener` のメソッドに `@ConcurrencyLimit(limitString = "${experiment.slow-limit}")` を付けて 1 にし、遅いイベントを 2 つ、続けて DB だけのイベントを出版した。

- 2 つ目の遅いリスナーは、制限の待ちに入る。待つ間はトランザクションを開いておらず、使用中の接続は 1 だった（`idle in transaction` が 1）。
- ただし待つスレッドは executor の枠を持つ。DB だけのリスナーは 1391 ms 後に動き始めた（今と同じ）。

接続は節約できるが、枠の取り合いは変わらない。

### 出版する要求のスレッドの待ち（別の問題）

`QueuedExecutorExperimentTest`。`spring.threads.virtual.enabled=false` と `spring.task.execution.pool.core-size=2`、`max-size=2`、`queue-capacity=50`（Boot の pool のプロパティだけ）で、`ThreadPoolTaskExecutor` になる。

- 遅い 2 つを動かし、3 つ目の遅いリスナーと DB だけのリスナーを出版した。出版は 7 ms と 6 ms で戻った（今は空きまで待つ）。使用中の接続は 2（動いている 2 つだけ）。
- DB だけのリスナーは、キューの順に並ぶので 1921 ms 後に動き始めた。キューだけでは枠の取り合いは解けない。

キューに入ったタスクは、プロセスが落ちると失われるが、出版は `event_publication` に永続化されており、Staleness Monitor と再投入（ADR-079）が `wait-age` の後に拾う。ADR-079 の Negative（`wait-age` を超えた最初の配信と再投入の重なり）は、今と同じ形で残る。

`spring.threads.virtual.enabled` は Tomcat などにも効く全体の設定なので、キュー付きの executor は、Boot の builder で自前の bean を作る形になる（`mode=force` の下で、`applicationTaskExecutor` の名前の bean は Boot が作る）。実装の形は作業の Issue で決める。

## 判断

1. 外部システムを呼ぶリスナーに専用の executor を持たせる（候補 1）。
2. 出版する要求のスレッドが executor の空きを待たないようにする（キュー付き）ことは、別の作業にする。ADR-079 の `lock-at-most-for`（再投入のジョブが空きを待つ時間を含む）も、この作業で見直す。
3. 候補 2 は #581 に残す。

候補 1 を見送る理由はない。「今はリスナーが 1 つだけ」は、見送りの理由にしない（後から入れても費用が変わらないことを理由にしない）。ただし、別の executor を足す費用は、新しい注釈 1 つと、注釈を読む 4 か所の修正である。

## 確かめられなかったこと

- 実際の決済代行（遅い呼び出しは `Thread.sleep` で代えた）。
- 終了の前に 2 つの executor を閉じる順と、`spring.task.execution.shutdown.*` が自前の bean に効くか。
- `formal-verify-order-payment` のモデルの実行。
- 本番の pool の大きさと負荷（#580）。テストの pool は 4 である。
- 候補 1 の注釈を実装した場合の、ArchUnit の規則と `TenantListenerAspect` の修正後の挙動（実験は、修正前の失敗までを確かめた）。

## 参照

- こちら：`application.yaml`（`spring.task.execution`、`spring.threads.virtual`）、`docs/integration/async-flow-control.md`、ADR-019、ADR-050、ADR-072、ADR-079、`OrderConfirmedListener`、`ChargeOrderCommandHandler`、`TenantListenerAspect`、`ResubmissionPolicies`、`EventPublicationResubmissionConfig`、`ClassRoleArchTest`、`DemoApplicationTest`。
- 外部：Spring Boot 4.1.2 の `TaskExecutorConfigurations`（spring-boot-autoconfigure の sources）、Spring Framework 7.0.x の `SimpleAsyncTaskExecutor`、`ConcurrencyThrottleSupport`、`@ConcurrencyLimit`。
- 実験の道具：`research/listener-executor-isolation/`（拡張子 `.java.txt`）。
