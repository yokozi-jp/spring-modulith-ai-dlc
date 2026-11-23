---
type: Convention
title: バッチのトランザクション、ロール、テナント
description: 業務のバッチの Step のトランザクション、CommandHandler の handle のトランザクションとロール、スパン、テナントごとの partition とテナントの束縛、並列の規則を定める規約で、ItemWriter と Tasklet から CommandHandler を呼ぶときやテナントの業務の表を書く Job を作るときに読む。
tags: [convention, batch, spring-batch, transaction, tenant, future-arch-guidelines]
---

# バッチのトランザクション、ロール、テナント

Step は DB のトランザクションを持たず、ItemWriter と Tasklet が呼ぶ CommandHandler の `handle` が 1 件か 1 単位ごとにトランザクションを開く。
テナントの業務の表を書く Job は、Step をテナントの `public_id` を名前にした partition に分け、`shared.batch` の Aspect が partition のテナントを束縛する。
Step の中で DB への接続を並列にせず、並列にするときは partition で分ける。
決定の理由は [ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## トランザクションとロール

- Step に `transactionManager` と `transactionAttribute` を渡さない。
  Spring Batch 6 の Step は、渡さなければ `ResourcelessTransactionManager` を使い、DB のトランザクションを持たない。
- 業務の表の書き込みは、ItemWriter と Tasklet が呼ぶ CommandHandler の `handle` のトランザクションで行う。
  `handle` のトランザクションの名前で、モジュールのロールとテナントが設定される（[DB 接続](../database/connections.md)、[PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md#テナントの設定)）。
- JobRepository のトランザクションは、アプリロールのままでテナントを設定せずに動く。
- 1 件ごとの commit が遅いときは、1 つの Command に複数の行のキーを持たせ、1 回の `handle` で 1 つのトランザクションにまとめる。
  Step にトランザクションを持たせて補わない。
- 長い `handle` のトランザクションで `statement_timeout` を上げるときは、[DBの時間の上限](../adr/ADR-055-set-db-time-limits-per-connection.md) に従い、そのトランザクションだけで上げる。

## スパン

- 共通カラムの `*_tx_id` には、Spring Batch の Job と Step の observation が作るスパンの trace ID が入る。
- 自分でスパンを始めない。
- Job と Step の定義のしかたは [クラスの役割：Job の設定](../backend/class-roles/job-config.md) に従う。

## テナントごとの partition

- テナントの業務の表を書く Job は、Step を `Partitioner` でテナントごとの partition に分ける。
- テナントの一覧は、`tenancy` の `<Feature>Queries` の、全テナントの `public_id` を返すメソッドで読む。
  このメソッドは、テナントの業務の表を書く Job と同じ変更で足す（[#667](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/667)）。
- partition の名前はテナントの `public_id` にし、連番にしない。
- 各 partition の ExecutionContext に、テナントの `public_id` を置く。
- ItemReader の `read`、ItemWriter の `write`、Tasklet の `execute` の間は、`shared.batch` の `TenantPartitionAspect` が partition のテナントを `TenantContext` に束縛する。
  ItemReader、ItemWriter、Tasklet は、自分でテナントを束縛しない。
- Aspect は、StepExecution の ExecutionContext にテナントがある Step の呼び出しだけを束縛し、テナントがない Step（テナントを持たない Job）は束縛せずに流す。
  テナントの業務の表を書く Step を partition にし忘れても、トランザクションマネージャーがテナントを束縛しない業務のトランザクションを始めないので、テナントなしでは書けない（[PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md#テナントの設定)）。
- 1 つのテナントの partition が失敗しても、ほかのテナントの partition は流れ、Job は FAILED で終わる。
  再開では、COMPLETED でない partition だけが流れる。

## 並列

- partition は既定の `SyncTaskExecutor` で順に流す。
- 並列にするときは、partition の `TaskExecutor` を渡し、DB の接続の数を [DB 接続](../database/connections.md) の接続の予算に収める。
- ItemReader、ItemWriter、Tasklet の中で、スレッドを作って DB に接続しない。

## 出典

- フューチャー株式会社「バッチ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forBatch/batch_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- 原文から採ったのは、DB への接続をプログラム内で並列にしない規則（原文の「バッチ処理の並列化」）だけである。
  ほかの規則は [ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md) の決定であり、原文にない。
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
