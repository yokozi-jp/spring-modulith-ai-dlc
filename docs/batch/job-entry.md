---
type: Convention
title: バッチのジョブの入口と起動
description: 業務のバッチの対象の範囲、Spring Batch の依存と構成、追いつきの Runner の起動と終わり方、終了コード、HTTP の route から起動しないこと、二重の起動、JobParameters と識別の引数と入力の検査を定める規約で、業務のバッチを作るときや起動のしかた、終了コード、識別の引数を決めるときに読む。
tags: [convention, batch, spring-batch, future-arch-guidelines]
---

# バッチのジョブの入口と起動

業務のバッチは Spring Batch の Job にし、同じイメージを `spring.batch.job.name` に Job の名前を渡して 1 回だけ起動する。
Job は `shared.batch` の追いつきの Runner が起動し、Runner はどの終わり方でも `System.exit` で終了コードを返す。
識別の引数は、期間で区切る Job では期間の初日、期間で区切らない Job では処理する単位の業務のキーにする。
業務のバッチを HTTP の route から起動しない。
決定の理由は [ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 対象

この規約と [バッチ](index.md) のほかの規約は、締め、集計、ファイルの配信と集信のような業務のバッチに適用する。
失敗したイベント出版の再投入のような短い保守の処理は、アプリの中の `@Scheduled` で動かし、バッチの規約を適用しない。
`@Scheduled` の処理を 1 つのインスタンスに絞る方法は [PostgreSQLの排他制御](../database/postgresql-concurrency-control.md#定期のジョブの排他) に従う。

## 依存と構成

- 依存は `spring-boot-starter-batch-jdbc` にし、テストには `spring-boot-starter-batch-jdbc-test` を足す。
- Spring Boot の自動構成を使い、`@EnableBatchProcessing`、`@EnableJdbcJobRepository`、`DefaultBatchConfiguration` を宣言しない。
- `application.yaml` で `spring.batch.job.enabled` を `false` にし、Spring Boot の `JobLauncherApplicationRunner` の Bean を作らせない。
  テストのプロファイルでも `false` のままにし、テストは Job を直接起動する（[バッチのテスト観点](testing.md)）。
- JobRepository とメタデータの表は [JobRepository](job-repository.md) に従う。

## 追いつきの Runner の起動

- Job は、`shared.batch` の `CatchUpJobRunner` だけが起動する。
  Runner は Spring Boot の `JobLauncherApplicationRunner` を継承した 1 つのクラスで、すべての Job を扱う。
  置き場所は [共有モジュール shared](../backend/shared-module.md) に従う。
- Runner の Bean は、`spring.batch.job.name` が空白でないときだけ作る。
  条件は `@ConditionalOnProperty` で書かず、`ResubmitOnceConfig.isResubmitOnceRequested` と同じく値の空白を調べる `Condition` で書く。
  `@ConditionalOnProperty` は、値が空白でも `false` でなければ一致する。
- Runner は `BatchProperties` から Job の名前を読み、`setJobName` に渡す。
- 期間で区切る Job は、Runner が [定時起動と追いつき](scheduling-and-catch-up.md#追いつきの-runner) の順で期間を流す。
  期間で区切らない Job は、渡された JobParameters で 1 回だけ流す。
- Runner は `execute` を上書きし、Job を流すたびに `super.execute` を呼ぶ。
  `JobOperator` を直接呼んで Job を流さない。
- `ExitCodeGenerator` の Bean を、Spring Boot の `JobExecutionExitCodeGenerator` のほかに作らない。
- 起動元（EventBridge Scheduler、Step Functions など）に依存する処理を Job に書かない。
  どの起動元から何度起動されても、[冪等性と再開](idempotency-and-restart.md) の規則で正しく終わるように作る。

## HTTP の route から起動しない

業務のバッチを、Controller などの HTTP の route から起動しない。
Spring Batch 6.0 のリファレンスは「Running Jobs from within a Web Container」で、Controller から非同期に Job を起動する形を載せているが、この形を採らない。
理由は次の 3 つである。

- 起動元（EventBridge Scheduler、Step Functions）との契約は終了コードである。Web から起動すると、終了コードを返せない。
- Job が Web のプロセスのスレッドと DB の接続を使い、Web のタスクの停止やデプロイで止まる。
- 起動の入口が増え、認証と CSRF を別に守る必要がある。未認証で許すのは health の probe だけである（[ADR-061](../adr/ADR-061-allow-only-health-probes-unauthenticated.md)）。

画面から要求する非同期の処理は、Job にせず、イベントとリスナーとステータスの表で作る（[非同期処理のステータス管理](../integration/async-job-status.md)）。

## Job の名前を渡した起動

Job の名前を渡した起動は、手での 1 回の再投入（[失敗したイベント出版の再投入](../integration/event-publication-resubmission.md)）と同じ形にする。

- Web のサーバーを動かさない（`spring.main.web-application-type=none`）。
- 失敗したイベント出版の定期の再投入（古い出版を `FAILED` に戻す処理を含む）を動かさない（`event-publication.resubmission.enabled=false`）。
  `ResubmitOnceConfig.ResubmitOnceEnvironment` は再投入の対象だけを見てこの 2 つを設定するので、Job の名前でも同じ設定を入れる。
- Job の名前と手での再投入の対象（`event-publication.resubmit-once.target`）を両方渡した起動は、どちらも流さずに終了コード 1 で終える。
  2 つの Runner がそれぞれ `System.exit` を呼ぶと、先に呼んだ側がもう一方の処理を途中で止める。
- Runner は、期間を流し終えるか止まったら、処理中のリスナーの完了を待ってから `System.exit(SpringApplication.exit(context))` を呼ぶ。
  待ち方は `ResubmitOnceConfig.awaitRunningListeners` と同じにする。
  このメソッドは `shared.eventresubmission` の package-private なので、`shared.batch` からも呼べる置き場所へ移して使う。
- Runner は、Job を流す前と流している間に出たすべての例外（`RuntimeException` と `Error`）を受け、ERROR で記録して、`SpringApplication.exit` に終了コード 1 を渡してから `System.exit` を呼ぶ。
  Spring Batch 6.0.6 の `AbstractJob.execute` は `Exception` を受けて JobExecution を FAILED にするが、`Error` と JobRepository の更新の失敗は外へ出る。
  `TaskExecutorJobLauncher` はそれを投げ直し、その JobExecution は STARTED のまま残る（[止まった期間を直す](runbook-stuck-period.md)）。
  例外が Runner の外へ出ると `System.exit` が呼ばれず、OTLP の送信のスレッドが残って JVM が終わらないことがある。

## 終了コード

終了コードは、Spring Boot の `JobExecutionExitCodeGenerator` が返す、COMPLETED でない最初の JobExecution の `BatchStatus` の序数にする。
Runner が例外を受けたときは 1 にする。
起動元は、0 だけを成功として扱う。

| 終了コード | 意味                                                                                                                                                                                                                                                             |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 0          | すべての JobExecution が COMPLETED。流す期間がなかったときも含む                                                                                                                                                                                                 |
| 1          | Runner が例外を受けた（引数の検査、`JobExecutionAlreadyRunningException`、`JobRestartException`、`JobInstanceAlreadyCompleteException`、JobExecution のない JobInstance の `IllegalStateException`、JobInstance の作成の一意制約の違反、Job の実行中の例外など） |
| 2          | STARTED。Job を同期で流した後には残らない                                                                                                                                                                                                                        |
| 3          | STOPPING。Job を同期で流した後には残らない                                                                                                                                                                                                                       |
| 4          | STOPPED                                                                                                                                                                                                                                                          |
| 5          | FAILED                                                                                                                                                                                                                                                           |
| 6          | ABANDONED。運用者が付ける状態で、Job を流した後には残らない                                                                                                                                                                                                      |
| 7          | UNKNOWN。commit の結果が分からず、運用者が確かめるまで再開できない                                                                                                                                                                                               |

STARTING の序数も 1 だが、Job を同期で流した後には残らない。
止まった期間の直し方は [止まった期間を直す](runbook-stuck-period.md) に従う。

## 二重の起動

- 同じ JobInstance の 2 本目は、多くの場合 Spring Batch が `JobExecutionAlreadyRunningException` で止める。
  JobInstance がまだない期間を 2 本が同時に起動すると、負けた側は JobInstance の作成で一意制約の違反になり、終了コード 1 で終わる。
  前の JobExecution が FAILED の JobInstance を 2 本が同時に再開すると、2 本とも流れることがある。
- COMPLETED か ABANDONED の JobInstance をもう一度起動すると、Spring Batch が `JobInstanceAlreadyCompleteException` で止め、終了コード 1 で終わる。
  Runner が最後の JobExecution を読んだ後に、ほかのプロセスが同じ期間を COMPLETED にしたときも同じである。
- 業務のバッチには ShedLock を使わない。
- 書き込みの正しさは [冪等な書き方](idempotency-and-restart.md#冪等な書き方) で守る。
  2 本が同時に流れても、DB の結果は 1 本だけ流したときと同じになる。
- 連携のジョブの多重起動と前回の異常終了の扱いは、[ジョブとファイルの順序制御](../integration/interface-job-ordering.md) にも従う。

## JobParameters と入力の検査

- Job が受け取る値は JobParameters にし、`JobParametersValidator` で起動の時点に検査する。
  不正な値は Job を流さずに失敗させる。
- Spring Batch 6.0.6 は、新しい JobInstance を作ってから引数を検査する。
  検査に失敗した起動は JobExecution のない JobInstance を残すので、同じ識別の引数で起動し直す前に [止まった期間を直す](runbook-stuck-period.md#jobexecution-のない-jobinstance-を消す) の手順で消す。
- コマンドラインでは、`--` を付けない `name=value` の形で渡す。
  `--` を付けた引数は Spring Boot の設定になり、JobParameters にならない。
  識別に使わない引数は、`name=value,java.lang.String,false` の形で識別の印を `false` にする。
- JobParameters には、秘密情報と個人データを入れない。
  JobParameters はメタデータの表に保存され、Spring Batch と Spring Boot の起動の INFO のログにすべて出る（ログの allowlist との合わせ方は [#669](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/669)）。
- デプロイの環境ごとに決まる値は、JobParameters でなく環境変数の設定にする。
  デプロイのときに決まる環境変数は、アプリで検査しない。
- 入力ファイルの検査は [連携ファイルの検証と取り込み範囲](../integration/interface-receiving-validation.md) に従う。
- 自分のシステムが作った処理対象のデータ（業務の表）は正しい前提で読み、Job の起動の時点で不整合を探さない。
  不整合が入ったときは、Job ではなくデータパッチで直す。
- Job が書いた結果を、同じ Job の中でアプリが検証しない。
  テストで確かめる（[バッチのテスト観点](testing.md)）。

## 識別の引数

- 期間で区切る Job は、期間の初日を識別の引数 `period` にし、追いつきの Runner が引数を作る（[定時起動と追いつき](scheduling-and-catch-up.md#期間と-jobinstance)）。
- 期間で区切らない Job は、処理する単位の業務のキーだけを識別の引数にする。
  集信の Job は、集信するファイルのオブジェクトのキー（[連携用オブジェクトストレージのバケットとキー](../integration/interface-object-storage-layout.md)）を識別の引数 `file` にする。
- 同じ引数で定時に起動する Job は、期間で区切る Job にする。
  期間で区切らない Job を同じ識別の引数で起動すると、2 回目から `JobInstanceAlreadyCompleteException` で止まる。
  定時の配信も、期間で区切る Job にする。
- 失敗した Job を再開するときは、起動元が識別の引数を失敗した起動と同じ値で渡す。
  識別の引数が違う起動は、別の JobInstance になり、失敗した JobInstance を再開しない。

## ログ

開始、終了、件数、進捗のログは [処理ごとに記録するログ](../observability/log-output-points.md#一括処理) に従う。
Spring Batch が出す Job と Step の終了のログで足りる項目は、アプリから重ねて出さない。

## 出典

- フューチャー株式会社「バッチ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forBatch/batch_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
