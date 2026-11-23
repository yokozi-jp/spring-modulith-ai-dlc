# 非同期の処理をワーカーの別プロセスに分けるかを比べる（#342 の調査）

この文書は #342 の調査の記録で、merge しない。
結論は #342 のコメントと同じで、作業は #658 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `3ba2640`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（`origin/main`、commit `a24fcf3`）である。
調べた日は 2026-11-23 である。
チケット #342 のコメントが指す向こうの #348（ワーカーとバッチを Web のアプリとして書く案の標準とアンチパターン）も、この文書で扱う。

## 結論

一部採用とする。
非同期のリスナーは、今のまま Web と同じプロセスで動かし、別のワーカーのプロセスには分けない。
取り込むのは、分けない理由と分け直すきっかけを docs に書くことと、業務のバッチを HTTP の route から起動しない規則である。
向こうのワーカーの構成（SQS を読む常駐のプロセスと Lambda の `handler(event)`）と、ワーカーを Web のアプリの route として書く形は取り込まない。

理由は 4 つある。

1. 分ける手段が、フレームワークにない。
   Spring Modulith は、出版した JVM に登録されたリスナーの分だけ出版の行を作り、そのリスナーをその JVM で呼ぶ。
   リスナーを持たない Web の JVM から出版すると、行は 0 件になり、別の JVM が配信する対象が残らない。
   分けるには外部ブローカーが要る。ブローカーは #311 と #313 で入れないと決めた。
   ブローカーなしの形は、Web でリスナーを登録して実行しない executor と、別の JVM が行を読んで配信する自前の処理で作れる（実験で動いた）。
   しかし、行を継続して読むコンシューマーが Spring Modulith にはなく、行を取り込む公開の操作 `IncompleteEventPublications` は 2.2 で削除予定になった。
   フレームワークに寄せる方針と逆になる。
2. 向こうの別プロセスは、キューを選んだ結果である。
   向こうは Outbox から SQS へ送り（ADR 0013）、キューを読む常駐のプロセスをワーカーにした。
   こちらにはキューがなく、レジストリが同じ役割を持つ（#313）。キューがなければ、読みに行くワーカーも要らない。
   向こうの #348 が調べた「ワーカーを Hono の route にする」「HTTP で push して受ける」も、キューからワーカーへの受け渡しの話で、こちらに当たる受け渡しがない。
3. 分ける利点が、今は小さい。
   リスナーは `OrderConfirmedListener` の 1 つで、1 件の最悪は約 3 秒である（ADR-079 の Negative）。
   リスナーが Web の応答を遅らせる問題（#557）は、同じプロセスの中の手段で解けるかをそちらで比べる。
   Web とは独立にスケールまたはデプロイする要件は、まだない。`docs/principles/right-sizing.md:24` は、個別サービスへの切り出しを、そのような要件が実際に生じてから ADR で決めるとする。
4. 費用が増える。
   最小の大きさ（0.5 vCPU、1 GB）でも、Fargate のタスクが 1 つ増えるごとに月に約 22 ドル（x86、東京）かかる。
   2 台に冗長化すれば約 45 ドルで、ADR-043 のサイドカーの分は大きさに含める。
   DB の接続の予算も、タスク数に比例して増える（ADR-019 の式）。
   さらに、`formal-verify-event-resubmission` のモデルの前提（どの台も配信と再投入と Staleness Monitor を持つ）が変わり、トレースは再配信で切れる。

この結論を「後から分けても費用が変わらない」とは書かない。
分け直す費用は小さくない（上の 4 のとおり、ブローカーかモデルの作り直しが要る）。
それでも今は分けないのは、利点が小さく、分ける手段がフレームワークにないからである。

分け直すきっかけは、次の 3 つである（#658 で docs に書く）。

- 外部ブローカーを入れる ADR を起こすとき。
- リスナーの負荷が Web の応答を遅らせることを負荷試験で測り、同じプロセスの手段で収まらないとき。
- Web と独立して、リスナーだけをスケールまたはデプロイする要件が生じたとき。

## 比べたもの

### 向こう

- `docs/adr/0013-api-on-ecs-and-lambda-web-functions.md`（入口、1 つのコンテナイメージ、バッチ、非同期処理）
- `docs/adr/0041-standard-queue-per-task-with-dlq.md`（ワーカーの入口、Hono にしない）、`0028`（`ManagedRuntime` を入口ごとに 1 つ）、`0032`（環境変数は入口で読む）
- `apps/worker/src/`：`poller.ts`、`lambda-handler.ts`、`notification-worker.ts`、`runtime.ts`、`server.ts`、`purge-idempotency-keys-job.ts`
- `docs/tooling/local-worker.md`
- Issue #348 の結論のコメントと、`research/hono-worker-batch.md`（ブランチ `research/hono-worker-batch`、commit `270cdd8`）

### こちら

- ADR-001、ADR-019、ADR-041、ADR-043、ADR-045、ADR-079、ADR-050、ADR-094、ADR-105、ADR-110
- docs：`docs/principles/right-sizing.md`、`docs/integration/async-messaging-design.md`、`async-flow-control.md`、`async-adoption-criteria.md`、`event-publication-resubmission.md`、`docs/nfr/`、`docs/container/`
- コード：`backend/Dockerfile`、`backend/src/main/resources/application.yaml`、`payment/application/OrderConfirmedListener.java`、`shared/infrastructure/persistence/EventPublicationResubmitOnce.java`、`EventPublicationResubmissionConfig.java`
- `docs/batch/` と ADR-115（ブランチ `docs/558-batch`、PR #656、まだ main にない）
- `.agents/skills/formal-verify-event-resubmission/`
- 決定済みの #311、#312、#313、#314、#319、#320

## 項目ごとの比較

| 向こうの決定                                                                                     | 向こうの場所                                        | こちらの扱い                                                                                                                                                                                                                                                 | 判定                                             |
| ------------------------------------------------------------------------------------------------ | --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------ |
| 業務の処理を `application` の関数にし、入口は変換だけにする                                      | ADR 0013:18、ADR 0041:24                            | Listener と ItemWriter が CommandHandler の `handle` を呼ぶ（`OrderConfirmedListener.java`、ADR-115）                                                                                                                                                        | 同じ決定                                         |
| ECS では API、ワーカー、バッチを 1 つのコンテナイメージにする                                    | ADR 0013:19                                         | イメージは `backend/Dockerfile` の 1 つ。手での再投入とバッチは同じイメージを 1 回だけ起動する（ADR-079:133、ADR-115）                                                                                                                                       | 同じ決定                                         |
| 締めのバッチは、ECS のタスクを 1 回だけ起動して動かす                                            | ADR 0013:20                                         | ADR-115 が同じ形で決めた。起動元（Scheduler、Step Functions）は決めない（#314）                                                                                                                                                                              | 同じ決定                                         |
| 期限切れの冪等キーを消す処理も、ECS のタスクで 1 回起動する                                      | ADR 0013:21、`purge-idempotency-keys-job.ts`        | #540 と #542 は、アプリの中の `@Scheduled` と ShedLock で消す（短い保守の処理は `@Scheduled`、#314）                                                                                                                                                         | 違う。#314 の結論に従う                          |
| 非同期は Outbox から SQS へ送り、ECS のワーカーが SQS を読む繰り返し、Lambda は `handler(event)` | ADR 0013:25、`poller.ts:33`、`lambda-handler.ts:13` | レジストリ（`modulith.event_publication`）が Outbox とキューを兼ね、リスナーは出版したプロセスで動く（ADR-001、#311、#313）                                                                                                                                  | 前提が違う。取り込まない                         |
| 停止の合図で、処理中の 1 件を終えてから止める                                                    | `server.ts:17-18`                                   | Web の要求は、Spring Boot の graceful shutdown（既定）が終わりを待つ。処理中のリスナーは、止めの時に DataSource が先に閉じて失敗しうるが、出版の行が残り再投入される（ADR-079 の Negative）。一度だけ起動する形は executor を閉じて 20 秒待つ（ADR-079:141） | 違う。リスナーは at-least-once で補う（ADR-079） |
| `ManagedRuntime` を入口ごとに 1 つ作り、環境変数は入口で読む                                     | ADR 0028、ADR 0032、`runtime.ts:42`                 | プロセスごとに `ApplicationContext` が 1 つ。環境変数は `@ConfigurationProperties`（ADR-008、ADR-079 の `Properties`）                                                                                                                                       | 同じ決定（Spring が持つ）                        |
| ワーカーを Hono のアプリにしない。外部から HTTP で受けるものだけを API の route にする           | ADR 0041:24、`:53`                                  | Controller から Job やリスナーを起動しない規則は、docs にない（Spring Batch のリファレンスは HTTP からの起動を載せている）                                                                                                                                   | 取り込む（#658）                                 |

### 向こうの #348 を Spring に写す

向こうの #348 は、ワーカーとバッチを Hono の route として書く 2 つの形を調べ、どちらも今の ADR 0013 より優れているとは言えないと結んだ。
Spring に写すと次のとおりである。

| 向こうの形                                                                                                                   | 向こうの調査の結果                                                                                                                           | Spring での形                                                                                                                                 | こちらの判定                                                                                                                                                                       |
| ---------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 形 1：メッセージや引数を Request に変え、`app.request()` で route を呼ぶ。ログ、OpenTelemetry、エラーの変換を API と共有する | 公式の事例が見つからず、自前の設計になる。共有できるのはログと OTel とエラーの変換だけ。SQS のイベントを `handle(app)` に渡すと 404 で消える | Listener や Job が、Controller を介して CommandHandler を呼ぶ形                                                                               | 取り込まない。共有したいもの（MDC、`pgm_cd`、トレース）は、Controller でなく Listener と CommandHandler の入口の Aspect が持つ（ADR-051、ADR-105、ADR-110）。HTTP を介す利点がない |
| 形 2：ワーカーを HTTP で待ち受けるサービスにし、Pipes や API destination から push する                                      | Web Functions は SQS から起動できず、API destination は 5 秒で timeout し、公開の入口が増える                                                | 業務のバッチや再投入の入口を、Controller の `POST` にして、スケジューラから HTTP で呼ぶ形（Spring Batch のリファレンスの Web Container の形） | 取り込まない。理由は「取り込まないもの」に書く                                                                                                                                     |

向こうの調査は、Hono に非 HTTP のイベントを route に流す公式の形がないことが、形 1 を退ける大きな理由だった。
Spring Batch は、HTTP から Job を起動する形を、リファレンスに載せている（次の節）。
この違いがあるので、こちらは docs で止める必要がある。

## 実験：リスナーを別の JVM で動かせるか

問い：Web の JVM はリスナーを持たず、別の JVM がリスナーを動かす構成を、Spring Modulith のレジストリだけで作れるか。

Spring Modulith 2.2.0 のソースを読むと、`PersistentApplicationEventMulticaster.multicastEvent` は、その JVM の `getApplicationListeners` で出版先を決め（`:88`）、その分だけ行を作り（`:111`）、同じ JVM でリスナーを呼ぶ（`:114`）。
実際に、別々の JVM で動かして確かめた。

- 構成：Spring Boot 4.1.2、Spring Modulith 2.1.2（こちらの版）と 2.2.0（最新）、PostgreSQL 18、`completion-mode: archive`、Java 25。
  アプリは使い捨てで、リポジトリには入れない。`/tmp` に作り、1 つの jar を引数で役割に分けて起動した。
- 起動の引数：`exp.listener`（リスナーの bean を置く）、`exp.executor=drop`（`applicationTaskExecutor` を、タスクを捨てる `TaskExecutor` にする）、`exp.publish`（トランザクションの中で 1 件出版し、行を表示する）、`exp.resubmit`（`IncompleteEventPublications` で未完了を再配信する）、`exp.failedonly`（`FailedEventPublications.resubmit` で再配信する）。

```java
@Component @ConditionalOnProperty("exp.listener")
static class Billing {
  @ApplicationModuleListener void on(OrderPlaced e) { System.out.println("LISTENER RAN pid=" + pid()); }
}

@Bean(name = "applicationTaskExecutor") @ConditionalOnProperty(name = "exp.executor", havingValue = "drop")
TaskExecutor dropExecutor() { return task -> System.out.println("EXECUTOR DROPPED"); }
```

結果は、2.1.2 と 2.2.0 で同じだった。

| 手順                                                                                                  | 出版の直後の `event_publication`             | 別の JVM（リスナーあり）の結果                                                          |
| ----------------------------------------------------------------------------------------------------- | -------------------------------------------- | --------------------------------------------------------------------------------------- |
| 1. リスナーの bean がない JVM から出版する                                                            | 0 行                                         | `IncompleteEventPublications` で再配信しても 0 行のまま。配信されない                   |
| 2. リスナーの bean がある JVM から、executor が捨てる設定で出版する                                   | 1 行（`PUBLISHED`、`completion_attempts=1`） | `IncompleteEventPublications` で再配信すると、別の JVM でリスナーが動き、archive へ移る |
| 3. 2 の行を、`FailedEventPublications.resubmit(ResubmissionOptions.defaults())` で再配信する（2.2.0） | 同上                                         | `PUBLISHED` の行は読まれず、リスナーは動かない                                          |
| 4. 3 の行の `status` を `FAILED` に更新してから、同じ呼び出しをする（2.2.0）                          | `FAILED`                                     | 別の JVM でリスナーが動き、archive へ移る                                               |

読み取れること：

- 手順 1：Web の JVM からリスナーを外す形は、出版の行が作られないので成り立たない。
- 手順 2：Web でリスナーを登録して実行しなければ、別の JVM が配信できる。ただし、Web の executor を自前で差し替え、別の JVM が行を読みに行く処理も自前で書くことになる。
- 手順 3 と 4：2.2.0 で `IncompleteEventPublications` は `@Deprecated(since = "2.2", forRemoval = true)` で、後継は `FailedEventPublications` とされる（`IncompleteEventPublications.java:26-28`）。
  `FailedEventPublications` は `FAILED` の行だけを再配信する。
  `PUBLISHED` の行が `FAILED` になるのは Staleness Monitor が `published` の閾値（ADR-079 では `wait-age`、本番想定 5 分）を過ぎた行を戻すときで、配信の遅れの下限が `wait-age` になる。
  手順 4 は、`FAILED` への変更を手の `UPDATE` で代えた。Staleness Monitor は動かしていない。
- 2.2.0 のソースで、定期に動く処理は Staleness Monitor（`StalenessMonitorConfiguration`）だけである。行を継続して読んで配信するコンシューマーはない。
  `spring.modulith.events.republish-outstanding-events-on-restart` は、起動時に 1 回だけ再配信する（`DefaultFailedEventPublications.java:48`）。

行の表は、`id`、`listener_id`、`event_type`、`serialized_event`、`publication_date`、`completion_date`、`status`、`completion_attempts`、`last_resubmission_date` の 9 列で、トレースもテナントも持たない。
別の JVM の配信は、発行元のトレースを引き継げない（上流の spring-modulith#555 は open）。
テナントは、ADR-105 が、リスナーの入口でイベントから取る。

## Spring のワーカーとバッチを Web のアプリとして書く形

### Spring Batch のリファレンス

- 起動の主な入口は、コマンドラインである。スケジューラの多くは OS のプロセス単位で動き、Job の成否を知らせる手段は終了コードだけだと書く（Spring Batch 6.1 の「Running a Job」の冒頭と Exit Codes）。
- 同じ頁の「Running Jobs from within a Web Container」は、HTTP からの起動を、レポート、その場の実行、Web アプリの支援のためのものとして載せる。
  Job は長く動くので、Controller は非同期に起動して `JobExecution` をすぐ返す、と書く。
  例は、`JobOperator`（5.2 までは `JobLauncher`）を使う `@Controller` である。6.0 と 6.1 で同じ文である。
- つまり Spring Batch は、Hono と違って、HTTP からの起動を公式に認めている。ただし用途は人が要求する起動で、スケジューラの起動ではない。

### こちらで HTTP から起動しない理由

- 起動元との契約が終了コードである。Spring Boot の `JobExecutionExitCodeGenerator` と `SpringApplication.exit` が返す値で、ECS の RunTask と Step Functions が成否を知る（ADR-115）。HTTP の応答では返せない。
- Web の中で非同期に動く Job は、Web のプロセスのスレッドと DB の接続を使い、Web のタスクの停止やデプロイで止まる（判断）。
  ECS の一度だけ起動するタスクは、Web と別のタスクで、停止の影響を受けない。
- 起動の入口が増える。ADR-061 は、認証なしで許す入口を liveness と readiness だけにする。起動の入口には認証、認可、CSRF の対策が別に要る。
- 画面から要求する非同期の処理は、すでに別の形がある。イベントとリスナーとステータスの表で作る（`docs/integration/async-job-status.md`）。
- Spring Batch Integration の `JobLaunchingGateway`（メッセージで Job を起動する）もある。Spring Integration の依存とブローカーが要るので、今は取り込まない。

### ECS の確認

- Dockerfile の `HEALTHCHECK`（`backend/Dockerfile:82`）は、ECS では動かない。ECS が見るのは、タスク定義の `healthCheck` だけである（[Determine Amazon ECS task health using container health checks](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/healthcheck.html)）。
  同じ頁は、タスク定義の `healthCheck` が UNHEALTHY になっても、サービスの外のタスク（RunTask の一度だけ起動するタスク）は止めないと書く。
  Web のサーバーを止めた常駐のワーカーを作るなら、タスク定義に liveness のコマンドが別に要る。分けない今は要らない。ECS のタスク定義がまだなく、今の構成に影響しない。

## 費用

本番の構成が、このリポジトリにまだない。
`infrastructure/` は `.gitkeep` だけで、ECS のタスク定義はない（`docs/integration/event-publication-resubmission.md:70`、ADR-045）。
ADR-041 は「タスクサイズは性能テストの結果から決める」とする。
そのため、大きさは仮に置く。

AWS の価格表（`AmazonECS` の `ap-northeast-1`、publicationDate 2026-11-11）から、Fargate の Linux の単価を読んだ。

| 項目         | x86 の単価（USD、時間） | ARM の単価（USD、時間） |
| ------------ | ----------------------- | ----------------------- |
| vCPU         | 0.05056                 | 0.04045                 |
| メモリ（GB） | 0.00553                 | 0.00442                 |

730 時間の月で計算した。

| タスクの大きさ | x86 の 1 タスク | x86 の 2 タスク | ARM の 1 タスク | ARM の 2 タスク |
| -------------- | --------------- | --------------- | --------------- | --------------- |
| 0.5 vCPU、1 GB | 22.49 ドル      | 44.98 ドル      | 17.99 ドル      | 35.98 ドル      |
| 1 vCPU、2 GB   | 44.98 ドル      | 89.97 ドル      | 35.98 ドル      | 71.96 ドル      |

- ADR-043 は、各 Fargate タスクに OpenTelemetry Collector のサイドカーを置くとする（ADR-043:42、`:65`）。サイドカーの CPU とメモリは、同じタスクの大きさに含まれる。
- 「2 台」は、ADR-079 と形式検証のモデルが置く、冗長にした場合の例である。本番の台数は未設定である。
- 分けると、Web のタスクの数は変わらず、ワーカーのタスクの分が足される。
- DB の接続の予算は、`maximum-pool-size × 最大アプリケーションタスク数 + マイグレーション接続 + 監視と運用の接続` である（ADR-019:62）。ワーカーのタスクの分の pool が足される。
- 実アプリの RSS と起動時間は測っていない（「確かめられなかったこと」）。1 GB に JVM、アプリ、サイドカーが入るかは、性能テストで決める。

## 取り込むもの

- **リスナーが出版したプロセスで動く理由と、分け直すきっかけを docs に書く**（#658）。
  `docs/integration/async-messaging-design.md` の「実行基盤」に節を足し、上の実験の事実（行は出版した JVM のリスナーの分だけ作られる、継続して読むコンシューマーがない、`IncompleteEventPublications` は削除予定）、分け直すきっかけ 3 つ、分けるときに作り直すもの（`formal-verify-event-resubmission`、DB の接続の予算、トレース）を書く。
  `docs/principles/right-sizing.md` の「再構築ポイントを先に書く」に当たる。ADR は起こさない。サービスの分割を ADR で決めることは、すでに right-sizing が定めている。
- **業務のバッチを HTTP の route から起動しない**（#658）。
  向こうの ADR 0041 の「ワーカーを Hono にしない」に当たる。`docs/batch/job-entry.md` に、理由 3 つ（終了コード、Web のスレッドと接続の共有と停止、入口の増加）と、画面から要求する非同期の処理の作り方を書く。#656 が先なら #656 に足す。

## 取り込まないもの

- **SQS を読む常駐のワーカー、Lambda の `handler(event)`、`batchItemFailures`**：キューがない（#311、#313）。
- **ワーカーを Web のアプリの route にし、Pipes や API destination から HTTP で受ける形**：HTTP の受け渡しを要するキューがない。公開の入口が増える。
- **別のプロセスのワーカーに、Web でリスナーを登録して実行しない executor を置く形**：実験で動いたが、自前の executor と取り込みの処理が要り、公開の操作 `IncompleteEventPublications` は削除予定である。配信の遅れの下限は `wait-age` になる。
- **期限切れの冪等キーを消す処理を、一度だけ起動するタスクにすること**：#314 が短い保守の処理を `@Scheduled` と決めた。#540 と #542 もそうしている。
- **ArchUnit で「Controller から `JobOperator` を呼ばない」と止める規則**：Job がまだない。最初の Job を作るときに、#558 が挙げる ArchUnit の規則と一緒に決める。実際に起きた誤りがない規則は、今は足さない。

## ほかの Issue との関係

- **#557（遅いリスナーの executor の枠）**：候補 3（ワーカーの別プロセス）は取らない。
  候補 1（外部システムを呼ぶリスナーに別の executor）と候補 2（外部の呼び出しをトランザクションの外へ）は、リスナーが Web と同じプロセスで動く前提のまま比べられる。
  その前提では、一度だけ起動するプロセス（再投入とバッチ）でも、同じ executor が動く。終了の前に executor を閉じる手順（ADR-079:141）は、2 つ目の executor を足すなら、そちらも閉じる必要がある。
  Web のタスクが止まるとき、処理中のリスナーは DataSource が先に閉じて失敗しうる（ADR-079 の Negative）。新しい executor を足すなら、閉じる順も決める。
  `formal-verify-event-resubmission` のモデルは、executor の空きの待ち（`QueueMax`）とリスナーの処理（`ListenerMax`）の上限を前提に置き、どちらも `lock-at-most-for` 以下とする。executor を分けると、この前提の置き方が変わるので、#557 の作業の Issue の受け入れ条件に再検査を入れる。
- **#313（Spring Modulith 2.2、`AbandonPolicy`）**：2.2.0 でも、リスナーの配信の場所は変わらない。`PersistentApplicationEventMulticaster` の該当の処理は同じである。
  `IncompleteEventPublications` が削除予定なので、#313 の作業で 2.2 に上げるとき、こちらのコードに使っている箇所がないことを確かめる（`grep` で `backend/src` に 0 件）。
- **#314（Spring Batch）と #558、PR #656**：決定と整合する。業務のバッチは同じイメージを 1 回だけ起動し、短い保守の処理は `@Scheduled` のまま。
  ADR-115 が決める一度だけ起動するプロセスは、Web を止めて再投入と Staleness Monitor を止め、リスナーの完了を待ってから JVM を終える。
  終了の前に executor を閉じて待つのは、ADR-079:141 では最大 20 秒である。`handle` が発行したイベントのリスナーがそれまでに終わらなければ、出版の行は `PUBLISHED` か `PROCESSING` で残り、Web の Staleness Monitor が `wait-age`（5 分）か `in-flight-staleness`（25 分）の後に `FAILED` に戻して再投入する。
  つまりバッチの JVM は、リスナーの配信を、最終的に Web の再投入に渡す。コードの読みで、Job がまだないので動かしていない。#656 のレビューで、ADR-115 の「処理中のリスナーの完了を待つ」が最大 20 秒であることを確かめてほしい。
- **#311、#312、#551**：変わらない。リスナーが同じプロセスで動く前提を使う。
- **#320（トレース）**：リスナーを別のプロセスに分けると、すべての配信でトレースが切れる。今は最初の配信で `propagate-context: true` が引き継ぎ、切れるのは再投入だけである（`async-observability.md:24`、#311 の「限界」）。
- **`formal-verify-event-resubmission`**：前提は変わらない。分けないので、再検査は #658 の受け入れ条件に入れない。
  一度だけ起動する JVM は、再投入と Staleness Monitor を止めて配信だけをする台で、モデルの 2 台（どちらも配信、再投入、Staleness Monitor を持つ）の動きの部分集合と読める。モデルでは確かめていない。
  分けるときは、配信する台と再投入する台が別になるので、モデルを作り直す。

## 確かめられなかったこと

- 本番の構成（ECS のタスク定義、タスクの大きさ、台数、オートスケール）がないので、費用は価格表と仮の大きさからの計算である。
- 実アプリの JVM の使用メモリと起動時間は測っていない。実アプリは Keycloak と、ロールを分けた DB と、compose の依存を要する。他の worktree が開発環境を使っている間は動かさなかった。
- 実験は、使い捨ての小さなアプリ（Spring Boot 4.1.2、Spring Modulith 2.1.2 と 2.2.0）で行った。こちらのアプリの本体（`ModuleRoleTransactionManager`、`TenantListenerAspect`、ShedLock）は通していない。
- Staleness Monitor は動かしていない。手順 4 の `FAILED` は手の `UPDATE` である。
- 外部ブローカー（Kafka、AMQP、JMS、SQS）を使うワーカーは動かしていない。ブローカーを入れる ADR のときに比べる（#313）。
- Spring Batch のリリースノートは読んでいない。最新は 6.1.0 で、こちらは Spring Boot 4.1.2 が管理する 6.0.6 である。HTTP からの起動の記述は、6.0 と 6.1 のリファレンスで同じである。
- Spring Modulith 2.2.0 のリリースノートの項目を読んだが、リスナーを別のプロセスで動かす機能は載っていない。2.1 の Namastack Outbox と JobRunr は #313 が取り込まないと決めた。
- 向こうの #348 は、AWS の上でなにも実行していない。向こうの結論のうち、AWS の挙動（`handle(app)` が SQS のイベントに 404 を返してメッセージが消えること、Web Functions を SQS から起動できないこと）は、向こうの記録を読んだだけで、こちらでは確かめていない。

## 確かめたこと

- 向こうの ADR 0013、0028、0032、0041、`apps/worker/src/*`、`docs/tooling/local-worker.md`、`research/hono-worker-batch.md` を `origin/main`（`3ba2640`）と該当ブランチから読んだ。
- 向こうの ADR 0013:18-25 と ADR 0041:24、`:53` で、入口、1 つのイメージ、Outbox から SQS、ワーカーを Hono にしない理由を読んだ。
- こちらの `docs/principles/right-sizing.md`（`:24` のサービスの分割、`:41-50` のテンプレートの利用先で変わる前提）、ADR-001、ADR-019（`:62` の接続の予算の式）、ADR-041、ADR-043、ADR-045、ADR-079、`async-messaging-design.md`、`async-flow-control.md`、`event-publication-resubmission.md`、`backend/Dockerfile`、`application.yaml` を `origin/main`（`a24fcf3`）から読んだ。
- `backend/src/main` の `@ApplicationModuleListener` は `OrderConfirmedListener` の 1 つだけである。
- `infrastructure/` は `.gitkeep` だけである。
- `EventPublicationResubmissionConfig.java:213` が、手での再投入のときに `spring.main.web-application-type` を設定する。一度だけ起動する形は、実装がすでにある。
- Spring Modulith 2.2.0 の sources の jar（Maven Central）で、`PersistentApplicationEventMulticaster.multicastEvent`（`:88`、`:111`、`:114`）、`IncompleteEventPublications`（`:26-28`）、`FailedEventPublications`、`StalenessMonitorConfiguration` を読んだ。
- 上の実験を、Spring Modulith 2.1.2 と 2.2.0 の両方で、別々の JVM（`java -jar`）で動かし、結果を表にした。
- Spring Modulith 2.2.0 のリファレンス（events）と GitHub のリリースノートを読んだ。Maven Central の最新は 2.2.0 である（2.1.3 も公開されている）。
- Spring Batch 6.1 と 6.0 のリファレンスの「Running a Job」を読んだ。6.1 は冒頭と Exit Codes、両方の版は「Running Jobs from within a Web Container」である。
- AWS の ECS のドキュメントで、タスク定義にない Docker の `HEALTHCHECK` を ECS が見ないことを読んだ。
- AWS の価格表（`AmazonECS`、`ap-northeast-1`、publicationDate 2026-11-11）から、Fargate の単価を読んだ。
- 地図 #305 と、#311、#313、#314、#557、#558（PR #656）、#540、#542 の本文と結論のコメントを読んだ。

## 出典

- [Spring Modulith: Working with Application Events](https://docs.spring.io/spring-modulith/reference/events.html)
- [spring-modulith-events-core 2.2.0 の sources](https://repo1.maven.org/maven2/org/springframework/modulith/spring-modulith-events-core/2.2.0/)
- [Spring Modulith 2.2.0 のリリースノート](https://github.com/spring-projects/spring-modulith/releases/tag/2.2.0)
- [Spring Batch: Running a Job（6.1）](https://docs.spring.io/spring-batch/reference/6.1/job/running.html)
- [Spring Batch: Running a Job（6.0）](https://docs.spring.io/spring-batch/reference/6.0/job/running.html)
- [Determine Amazon ECS task health using container health checks](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/healthcheck.html)
- [AWS 価格表（Amazon ECS、ap-northeast-1）](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonECS/current/ap-northeast-1/index.json)
- 向こう：[#348 の結論のコメント](https://github.com/yokozi-jp/agent-software-factory/issues/348)、[research/hono-worker-batch.md](https://github.com/yokozi-jp/agent-software-factory/blob/270cdd81165536b8a2ba7cadb41fe45f4a4bac89/research/hono-worker-batch.md)
