---
type: Convention
title: バッチのジョブの入口と起動
description: 業務のバッチを Spring Batch の Job にするときの依存と構成、JobRepository、起動の Runner、終了コード、JobParameters と入力の検査を定める規約で、業務のバッチを作るときや起動のしかたと終了コードを決めるときに読む。
tags: [convention, batch, spring-batch, future-arch-guidelines]
---

# バッチのジョブの入口と起動

業務のバッチは Spring Batch の Job にし、同じイメージを `spring.batch.job.name` に Job の名前を渡して 1 回だけ起動する。
Job は `shared.batch` の追いつきの Runner が起動し、Runner が `System.exit` で `BatchStatus` の序数の終了コードを返す。
JobParameters は `JobParametersValidator` で起動の時点に検査する。
決定の理由は [ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 対象

この規約は、締め、集計、ファイルの配信と集信のような業務のバッチに適用する。
失敗したイベント出版の再投入のような短い保守の処理は、アプリの中の `@Scheduled` で動かし、この規約を適用しない。
`@Scheduled` の処理を 1 つのインスタンスに絞る方法は [PostgreSQLの排他制御](../database/postgresql-concurrency-control.md#定期のジョブの排他) に従う。

## 依存と構成

- 依存は `spring-boot-starter-batch-jdbc` にし、テストには `spring-boot-starter-batch-jdbc-test` を足す。
- Spring Boot の自動構成を使い、`@EnableBatchProcessing`、`@EnableJdbcJobRepository`、`DefaultBatchConfiguration` を宣言しない。
- `application.yaml` で `spring.batch.job.enabled` を `false` にし、Spring Boot の `JobLauncherApplicationRunner` の Bean を作らせない。
  テストのプロファイルでも `false` のままにし、テストは Job を直接起動する（[バッチのテスト観点](testing.md)）。

## JobRepository

- JobRepository は Spring Boot が構成する JDBC の JobRepository にする。
  `ResourcelessJobRepository` を使わない。
- メタデータの表は `modulith` スキーマに置き、`spring.batch.jdbc.table-prefix` を `modulith.BATCH_` にする。
- メタデータの表は、Spring Batch の `schema-postgresql.sql` と同じ形を Liquibase の changeset で作り、`spring.batch.jdbc.initialize-schema` を `never` にする。
  changeset の書き方は [DBマイグレーション規約](../database/migrations.md) に従う。
- メタデータの表と sequence の SELECT、INSERT、UPDATE、DELETE と sequence の USAGE は、アプリロールにだけ与える。
  モジュールのロールには与えない。
- ジョブの実行の記録の表を自分で作らない。
  開始、終了、件数、成否は JobRepository の表から読む。
- メタデータの表の時刻の列は、JVM の既定のタイムゾーンの時刻である。
  Job を動かす JVM は、コンテナの外でも `-Duser.timezone=UTC` にする（[日時とタイムゾーンの規約](../datetime/timezone-conventions.md#バックエンド)）。
  テストの JVM は UTC でないので（同じ規約の[テスト](../datetime/timezone-conventions.md#テスト)）、テストでメタデータの時刻を UTC として比べない。
- メタデータの表の時刻を業務の判断に使わない。
  業務の期間は JobParameters で持つ。

## 追いつきの Runner の起動

- Job は、`shared.batch` の追いつきの Runner だけが起動する。
  Runner は Spring Boot の `JobLauncherApplicationRunner` を継承した 1 つのクラスで、すべての Job を扱う。
  置き場所は [バックエンドアーキテクチャ](../backend/architecture.md#共有モジュール-shared) に従う。
- Runner の Bean は、`spring.batch.job.name` が空白でないときだけ作る。
  Runner は `BatchProperties` から Job の名前を読み、`setJobName` に渡す。
- 期間で区切る Job は、Runner が [定時起動と追いつき](scheduling-and-catch-up.md#追いつきの-runner) の順で期間を流す。
  期間で区切らない Job は、渡された JobParameters で 1 回だけ流す。
- Runner は `execute` を上書きし、Job を流すたびに `super.execute` を呼ぶ。
  `JobOperator` を直接呼んで Job を流さない。
- `ExitCodeGenerator` の Bean を、Spring Boot の `JobExecutionExitCodeGenerator` のほかに作らない。
- Job の名前を渡した起動では、次のとおりにする。
  形は、手での 1 回の再投入（[失敗したイベント出版の再投入](../integration/event-publication-resubmission.md)）と同じにする。
  - Web のサーバーを動かさない（`spring.main.web-application-type=none`）。
  - 失敗したイベント出版の再投入と Staleness Monitor を動かさない。
  - Runner は、期間を流し終えるか止まったら、処理中のリスナーの完了を待ってから `System.exit(SpringApplication.exit(context))` を呼ぶ。
  - Job を流す前の例外（下の表の終了コード 1）は Runner が受け、ERROR で記録して、`SpringApplication.exit` に終了コード 1 を渡す。
- 起動元（EventBridge Scheduler、Step Functions など）に依存する処理を Job に書かない。
  どの起動元から何度起動されても、[冪等性と再開](idempotency-and-restart.md) の規則で正しく終わるように作る。

## 終了コード

終了コードは、Spring Boot の `JobExecutionExitCodeGenerator` が返す、COMPLETED でない最初の JobExecution の `BatchStatus` の序数にする。
起動元は、0 だけを成功として扱う。

| 終了コード | 意味                                                                                                                                       |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| 0          | すべての JobExecution が COMPLETED。流す期間がなかったときも含む                                                                           |
| 1          | Job を流す前に失敗した（引数の検査、`JobExecutionAlreadyRunningException`、`JobRestartException`、JobInstance の作成の一意制約の違反など） |
| 2          | STARTED。Job を同期で流した後には残らない                                                                                                  |
| 3          | STOPPING。Job を同期で流した後には残らない                                                                                                 |
| 4          | STOPPED                                                                                                                                    |
| 5          | FAILED                                                                                                                                     |
| 6          | ABANDONED。運用者が付ける状態で、Job を流した後には残らない                                                                                |
| 7          | UNKNOWN。commit の結果が分からず、運用者が確かめるまで再開できない                                                                         |

STARTING の序数も 1 だが、Job を同期で流した後には残らない。
止まった期間の直し方は [冪等性と再開](idempotency-and-restart.md#止まった期間) に従う。

## JobParameters と入力の検査

- Job が受け取る値は JobParameters にし、`JobParametersValidator` で起動の時点に検査する。
  不正な値は Job を流さずに失敗させる。
- コマンドラインでは、`--` を付けない `name=value` の形で渡す。
  `--` を付けた引数は Spring Boot の設定になり、JobParameters にならない。
- 再開するときは、識別に使う引数を最初と同じ値で渡す。
  期間で区切る Job では、追いつきの Runner が引数を作る。
- JobParameters には、秘密情報と個人データを入れない。
  JobParameters はメタデータの表に保存され、Spring Batch と Spring Boot の起動の INFO のログにすべて出る。
  ログの allowlist との合わせ方は [#669](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/669) で決める。
- デプロイの環境ごとに決まる値は、JobParameters でなく環境変数の設定にする。
  デプロイのときに決まる環境変数は、アプリで検査しない。
- 入力ファイルの検査は [連携ファイルの検証と取り込み範囲](../integration/interface-receiving-validation.md) に従う。
- 自分のシステムが作った処理対象のデータ（業務の表）は正しい前提で読み、Job の起動の時点で不整合を探さない。
  不整合が入ったときは、Job ではなくデータパッチで直す。
- Job が書いた結果を、同じ Job の中でアプリが検証しない。
  テストで確かめる（[バッチのテスト観点](testing.md)）。

## ログ

開始、終了、件数、進捗のログは [処理ごとに記録するログ](../observability/log-output-points.md#一括処理) に従う。
Spring Batch が出す Job と Step の終了のログで足りる項目は、アプリから重ねて出さない。

## 出典

- フューチャー株式会社「バッチ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forBatch/batch_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
