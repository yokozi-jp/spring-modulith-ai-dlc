---
type: Convention
title: バッチのジョブの入口と起動
description: 業務のバッチを Spring Batch の Job にするときの依存と構成、JobRepository の置き場所、起動の形、終了コード、JobParameters、置き場所と役割、トランザクションとロール、スパンとテナントを定める規約。業務のバッチを作るとき、ジョブの起動のしかたや終了コードを決めるときに読む。
tags: [convention, batch, spring-batch, future-arch-guidelines]
---

# バッチのジョブの入口と起動

業務のバッチは Spring Batch の Job にし、同じイメージを `spring.batch.job.name` に Job の名前を渡して 1 回だけ起動する。
結果は `BatchStatus` の序数の終了コードで返し、COMPLETED だけを 0 にする。
Step は DB のトランザクションを持たず、ItemWriter と Tasklet が呼ぶ CommandHandler の `handle` がトランザクションを開く。
決定の理由は [ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 対象

この規約は、締め、集計、ファイルの配信と集信のような業務のバッチに適用する。
失敗したイベント出版の再投入のような短い保守の処理は、アプリの中の `@Scheduled` で動かし、この規約を適用しない。
`@Scheduled` の処理を 1 つのインスタンスに絞る方法は [PostgreSQLの排他制御](../database/postgresql-concurrency-control.md#定期のジョブの排他) に従う。

## 依存と構成

- 依存は `spring-boot-starter-batch-jdbc` にし、テストには `spring-boot-starter-batch-jdbc-test` を足す。
- Spring Boot の自動構成を使い、`@EnableBatchProcessing`、`@EnableJdbcJobRepository`、`DefaultBatchConfiguration` を宣言しない。
  宣言すると、`spring.batch.*` の設定と起動の Runner の自動構成が退く。
- JobRepository は Spring Boot が構成する JDBC の JobRepository にする。
  `ResourcelessJobRepository` を使わない。
- メタデータの表は `modulith` スキーマに置き、`spring.batch.jdbc.table-prefix` を `modulith.BATCH_` にする。
- メタデータの表は、Spring Batch の `schema-postgresql.sql` と同じ形を Liquibase の changeset で作り、`spring.batch.jdbc.initialize-schema` を `never` にする。
  changeset の書き方は [DBマイグレーション規約](../database/migrations.md) に従う。
- メタデータの表と sequence の SELECT、INSERT、UPDATE、DELETE と sequence の USAGE は、アプリロールにだけ与える。
  モジュールのロールには与えない。
- ジョブの実行の記録の表を自分で作らない。
  開始、終了、件数、成否は JobRepository の表から読む。

## 起動の形

- 同じイメージを、`spring.batch.job.name` に Job の名前を渡して 1 回だけ起動する。
- Job の名前を渡した起動では、次のとおりにする。
  形は、手での 1 回の再投入（[失敗したイベント出版の再投入](../integration/event-publication-resubmission.md)）と同じにする。
  - Web のサーバーを動かさない（`spring.main.web-application-type=none`）。
  - 失敗したイベント出版の再投入と Staleness Monitor を動かさない。
  - Job が終わったら、処理中のリスナーの完了を待ってから、`SpringApplication.exit` の終了コードで JVM を終える。
    `handle` が発行したイベントのリスナーは、同じプロセスで非同期に動くためである。
- Job の名前を渡さない通常の起動では、`spring.batch.job.enabled=false` にし、Job を動かさない。
- 起動元（EventBridge Scheduler、Step Functions など）に依存する処理を Job に書かない。
  どの起動元から何度起動されても、[冪等性と再開](idempotency-and-restart.md) の規則で正しく終わるように作る。

## 終了コード

終了コードは、Spring Boot の `JobExecutionExitCodeGenerator` が返す `BatchStatus` の序数にする。
起動元は、0 だけを成功として扱う。

| 終了コード | 意味                                                                            |
| ---------- | ------------------------------------------------------------------------------- |
| 0          | すべての JobExecution が COMPLETED。処理する期間がなかったときも含む            |
| 1          | Job を流す前に失敗した（`JobExecutionAlreadyRunningException`、引数の検査など） |
| 4          | STOPPED                                                                         |
| 5          | FAILED                                                                          |
| 7          | UNKNOWN。commit の結果が分からず、運用者が確かめるまで再開できない              |

## JobParameters

- Job が受け取る値は JobParameters にし、`JobParametersValidator` で起動の時点に検査する。
  不正な値は Job を流さずに失敗させる。
- コマンドラインでは、`--` を付けない `name=value` の形で渡す。
  `--` を付けた引数は Spring Boot の設定になり、JobParameters にならない。
- 再開するときは、識別に使う引数を最初と同じ値で渡す。
  期間で区切る Job では、追いつきの Runner が引数を作る（[定時起動と追いつき](scheduling-and-catch-up.md)）。
- JobParameters には、秘密情報と個人データを入れない。
  JobParameters はメタデータの表に保存され、Spring Batch の INFO のログにも出る。
  ログに出してよい値は [可観測性データの規約](../observability/conventions.md) に従う。
- デプロイの環境ごとに決まる値は、JobParameters でなく環境変数の設定にする。

## 置き場所と役割

- Job を持つ機能モジュールの `com.example.demo.<module>.batch` に、Job の設定、ItemReader、ItemWriter、Tasklet を置く。
  役割の規則は [クラスの役割](../backend/class-roles/index.md) の Job の設定、ItemReader、ItemWriter、Tasklet に従う。
- Job の名前は `<module>.<JobName>` にする（`ordering.CloseMonth`）。
  JobName は、業務の動詞から始まるパスカルケースにする。
  Job の名前は、設計書の識別子にも使う（[機能の設計書](../writing/design-doc-feature-specs.md)）。

## トランザクションとロール

- Step に `transactionManager` と `transactionAttribute` を渡さない。
  Spring Batch 6 の Step は、渡さなければ `ResourcelessTransactionManager` を使い、DB のトランザクションを持たない。
- 業務の表の書き込みは、ItemWriter と Tasklet が呼ぶ CommandHandler の `handle` のトランザクションで行う。
  `handle` のトランザクションの名前で、モジュールのロールとテナントが設定される（[DB 接続](../database/connections.md)、[PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md#テナントの設定)）。
- JobRepository のトランザクションは、アプリロールのままでテナントを設定せずに動く。
- 1 件ごとの commit が遅いときは、1 つの Command に複数の行のキーを持たせ、1 回の `handle` で 1 つのトランザクションにまとめる。
  Step にトランザクションを持たせて補わない。
- 長い `handle` のトランザクションで `statement_timeout` を上げるときは、[DBの時間の上限](../adr/ADR-055-set-db-time-limits-per-connection.md) に従い、そのトランザクションだけで上げる。

## スパンとテナント

- 共通カラムの `*_tx_id` には、Spring Batch の Job と Step の observation が作るスパンの trace ID が入る。
  自分でスパンを始めない。
  Spring Boot が observation を渡すのは Bean の Job と Step だけなので、Job と Step は Bean で定義する。
- テナントの業務の表を書く Job は、Step を `Partitioner` でテナントごとの partition に分ける。
  テナントの一覧は `tenancy` の `<Feature>Queries` で読み、各 partition の ExecutionContext にテナントの `public_id` を置く。
- ItemReader の `read`、ItemWriter の `write`、Tasklet の `execute` の間は、`shared.infrastructure.persistence` の Aspect が、partition のテナントを `TenantContext` に束縛する。
  ItemReader、ItemWriter、Tasklet は、自分でテナントを束縛しない。
- 1 つのテナントの partition が失敗しても、ほかのテナントの partition は流れ、Job は FAILED で終わる。
  再開では、COMPLETED でない partition だけが流れる。
- partition は既定の `SyncTaskExecutor` で順に流す。
  並列にするときは、partition の `TaskExecutor` を渡し、DB の接続の数を [DB 接続](../database/connections.md) の接続の予算に収める。
  ItemWriter や Tasklet の中で、スレッドを作って DB に接続しない。
  トランザクションがスレッドごとに分かれ、Job の成否と一致しなくなるためである。

## ログ

開始、終了、件数、進捗のログは [処理ごとに記録するログ](../observability/log-output-points.md#一括処理) に従う。
Spring Batch が出す Job と Step の終了のログで足りる項目は、アプリから重ねて出さない。

## 出典

- フューチャー株式会社「バッチ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forBatch/batch_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
