---
type: ADR
title: 'ADR-115: 業務のバッチを Spring Batch の Job で作り、1 つの期間を 1 つの JobInstance にして毎日追いつく'
description: 締め、集計、ファイルの配信と集信のような業務のバッチを Spring Batch 6 の Job にし、JobRepository を modulith スキーマに置き、Step のトランザクションを持たずに CommandHandler の handle に任せる決定。入口、追いつき、再開、二重の起動、テナント、スパン、置き場所と役割を決める。
tags: [adr, batch, spring-batch, architecture, transaction]
---

# ADR-115: 業務のバッチを Spring Batch の Job で作り、1 つの期間を 1 つの JobInstance にして毎日追いつく

## Status

Proposed

## Date

2026-11-23

## Context

このリポジトリは、バッチを前提にした規則をすでに持つ。
[ADR-041](ADR-041-run-ecs-tasks-on-fargate.md) はバッチのタスクを Fargate で動かすとし、[ADR-055](ADR-055-set-db-time-limits-per-connection.md) はバッチのトランザクションだけ `statement_timeout` を上げてよいとする。
[ADR-051](ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md) は、CommandHandler と Listener を通らない書き込みの入口を作るときに役割と pointcut を決め直すとしている。
しかし、ジョブの入口、追いつき、冪等性と再開、二重の起動、オンラインとの排他、試験を定める文書がない。
調べた事実と出典は [#314](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/314) のコメントにある。

[#314](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/314) の最初の結論は、Spring Batch を入れず、自前の入口と「最後に処理した期間」の列で追いつく形だった。
その後、自作をできる限りやめ、フレームワークの仕組みに寄せる方針で、Spring Batch を使う形に改めた。
Spring Batch のリファレンスは、業務の行に処理済みの列を持ち、reader に位置を保存させない形を process indicator として示している。
自前の形で作るはずだった入口、追いつき、再開、二重の起動の検知、実行の履歴は、Spring Batch と Spring Boot の仕組みで置き換えられる。

このリポジトリの Spring Boot は 4.1.2 で、その依存の管理が決める Spring Batch は 6.0.6 である（`backend/build.gradle` と `spring-boot-dependencies-4.1.2.pom`）。
この ADR が挙げる Spring Batch と Spring Boot の振る舞いは、この版のリファレンスとソースで確かめた。

決める前に、使い捨てのテストで次を確かめた（テストはコミットしていない）。
テストは、テストの依存に `spring-batch-core` を一時的に足し、Testcontainers の PostgreSQL に JDBC の JobRepository の表を `modulith` スキーマに作って流した。
業務の書き込みには、既存の注文の確定（`ConfirmOrderCommandHandler`）を使った。

- Step に `ResourcelessTransactionManager` を使い、ItemWriter が 1 件ごとに `handle` を呼ぶと、注文の確定は成功した。
  アプリロールは `ordering` スキーマを読めない（同じテストで `permission denied for schema ordering`）ので、`handle` のトランザクションが `ordering_module` に切り替わったことになる。
- 5 件を chunk の大きさ 2 で流し、4 件目で 1 度だけ失敗させると、1 回目は 3 件を確定して FAILED になった。
  同じ識別の引数で起動し直すと同じ JobInstance が再開して COMPLETED になり、3 件目がもう一度 ItemWriter に渡った。
  ItemWriter が確定済みの注文を飛ばしたので、5 件がそれぞれ 1 回だけ確定された。
  COMPLETED の JobInstance をもう一度起動すると `JobInstanceAlreadyCompleteException` になった。
- Step に `ModuleRoleTransactionManager` と、モジュールの名前を付けた `transactionAttribute` を渡すと、Step は `permission denied for table batch_step_execution_context` で FAILED になった。
  `ChunkOrientedStep` は、chunk のトランザクションの中で Step の状態を JobRepository に保存する（Spring Batch 6.0.6 のソースの `saveState`）。
  その保存が `ordering_module` のロールで流れ、メタデータの表に権限がなかった。
- Spring Boot の構成と同じく Job と Step に `ObservationRegistry` を渡すと、ItemWriter の中に現在のスパンがあった。
  1 回の JobExecution の中の書き込みは、同じ trace ID になった。

## Decision

### 入口の形

- 短い保守の処理（[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md) の再投入、[#542](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/542) の archive の削除）は、これまでどおりアプリの中の `@Scheduled` で動かす。
- 業務のバッチ（締め、集計、ファイルの配信と集信）は、Spring Batch の Job にする。
- 依存は `spring-boot-starter-batch-jdbc` と、テストの `spring-boot-starter-batch-jdbc-test` にする。
  どちらも最初のジョブと同じ変更で足す。
- Spring Boot の Spring Batch の自動構成を使い、`@EnableBatchProcessing`、`@EnableJdbcJobRepository`、`DefaultBatchConfiguration` を宣言しない。
  宣言すると、Spring Boot の自動構成（`spring.batch.*` の設定と、起動のときにジョブを流す Runner）が退く。
- 同じイメージを、`spring.batch.job.name` に Job の名前を渡して 1 回だけ起動する。
  名前を渡した起動では、Web のサーバー、再投入、Staleness Monitor を止め、処理中のリスナーの完了を待ってから JVM を終える。
  形は、手での 1 回の再投入（ADR-079 の `EventPublicationResubmitOnce`）と同じにする。
  名前を渡さない通常の起動では `spring.batch.job.enabled=false` にし、Job を動かさない。
- 終了コードは、Spring Boot の `JobExecutionExitCodeGenerator` が返す `BatchStatus` の序数を `SpringApplication.exit` で返す。
  COMPLETED は 0、FAILED は 5 である。
  起動の前に失敗したとき（`JobExecutionAlreadyRunningException` など）は 1 で JVM を終える。
- パラメータは JobParameters で受け取り、`JobParametersValidator` で検査する。

### 起動元

EventBridge Scheduler、Step Functions などの起動元は決めない。
AWS の構成は、[ADR-045](ADR-045-remove-aws-docs-and-production-cd-example.md) の AWS の規約の書き直しで決める。
Job は、どの起動元から何度起動されても、下の JobInstance と再開で正しく終わるように作る。

### JobRepository

- Spring Boot が構成する JDBC の JobRepository を使う。
  Spring Batch 6 の既定の `ResourcelessJobRepository` は、再開と並行の実行に対応しないので使わない。
- メタデータの表（`BATCH_*`）は `modulith` スキーマに置き、`spring.batch.jdbc.table-prefix` を `modulith.BATCH_` にする。
  表は Liquibase の changeset で、Spring Batch の `schema-postgresql.sql` と同じ形に作る。
  `spring.batch.jdbc.initialize-schema` は `never` にする。
- 表と sequence の権限は、アプリロールにだけ与え、モジュールのロールには与えない。
  JobRepository は自分のトランザクションを開き、その名前は機能モジュールのパッケージで始まらないので、`ModuleRoles.roleFor` がモジュールを返さず、アプリロールのまま動く（[ADR-094](ADR-094-switch-to-module-database-roles.md)）。
  使い捨てのテストでも、アプリロールにだけ権限を与えたメタデータの表に書けた。
  テナントも設定しない。
  メタデータの表は、`modulith` スキーマのほかの表と同じく RLS の外に置く（[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）。
- 実行の履歴（開始、終了、件数、成否）はこの表で持ち、自前の実行の記録の表は作らない。
- メタデータの表の時刻の列は Spring Batch の決めた `TIMESTAMP` のままにし、業務の判断に使わない。
  業務の期間は JobParameters で持つ。

### JobInstance と追いつき

- 期間で区切る Job は、対象の期間の初日を、業務タイムゾーン（[ADR-046](ADR-046-derive-local-dates-with-configured-business-zone.md)）の `LocalDate` で、識別に使う JobParameter `period` にする。
  1 つの期間を 1 つの JobInstance にする。
- 月次の Job も毎日起動する。
- 期間で区切る Job は、Spring Boot の `JobLauncherApplicationRunner` を継承して `execute` を上書きした、追いつきの Runner で起動する。
  Runner は、Job の最後の JobInstance が COMPLETED ならその次の期間から、COMPLETED でなければその期間から、起動した時刻より前に終わった期間までを古い順に流す。
  JobInstance がまだなければ、Job の設定が決める最初の期間から流す。
  1 つの期間が COMPLETED にならなければ、そこで止め、残りの期間を次の起動に回す。
  処理する期間がなければ、何も流さずに終了コード 0 で終える。
- 期間を引数で渡して流す起動は作らない。
- `JobParametersIncrementer` は使わない。
  Spring Batch 6.0.6 の `SimpleJobOperator.start` は、incrementer を持つ Job では渡した引数を捨て、前の JobExecution の引数から次の引数を作るので、失敗した期間を飛ばす。
- 自前の「最後に処理した期間」の列は作らない。
- 期間の境界は Java で業務タイムゾーンから求めて SQL に引数で渡し（[ADR-114](ADR-114-pass-calendar-dates-as-full-date-and-bind-boundaries.md)）、半開区間で書く。

### 再開

- 失敗した JobInstance は、次の起動で追いつきの Runner が同じ識別の引数で流し、Spring Batch の再開で、失敗した Step と chunk から続ける。
- DB から読む reader は process indicator にする。
  業務の行の状態の列で未処理だけを選び、読んだ位置を ExecutionContext に保存しない。
  ファイルから読む reader は、Spring Batch の reader の既定のとおり読んだ位置を保存してよい。
- 1 件の失敗は、Spring Batch の skip と retry で扱う。

### 二重の起動

- 同じ JobInstance の 2 本目は、Spring Batch が `JobExecutionAlreadyRunningException` で止める。
  COMPLETED の JobInstance の再起動は、`JobInstanceAlreadyCompleteException` で止める。
- 業務のバッチには ShedLock を使わない。
- それでも、書き込みは業務のキーの一意制約と、状態の遷移を WHERE に書いた UPDATE で守る（[#551](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/551) と同じ）。

### トランザクションとロール

- Step に `transactionManager` と `transactionAttribute` を渡さない。
  Spring Batch 6 の Step は、渡さなければ `ResourcelessTransactionManager` を使い、Step は DB のトランザクションを持たない。
- ItemWriter と Tasklet は、1 件か 1 単位ごとに CommandHandler の `handle` を呼び、`handle` が自分の名前のあるトランザクションを開く。
  [ADR-050](ADR-050-define-backend-class-roles-and-naming.md) の「トランザクションの境界は `handle`」と、ADR-094 のロールの切り替えをそのまま使う。
- `handle` は 1 件ごとに commit するので、落ちた後の再開と skip の再試行で、同じ行が `handle` にもう一度渡る。
  `handle` を冪等にし（process indicator、業務のキーの一意制約）、同じ結果に収める。
- 1 件ごとの commit が遅いジョブは、Step にトランザクションを持たせず、1 つの Command に複数の行のキーを持たせ、1 回の `handle` で 1 つのトランザクションにまとめる。

### 置き場所と役割

- Job を持つ機能モジュールの中に `batch` の package（`com.example.demo.<module>.batch`）を作り、次の役割を置く。
  役割の規則は [クラスの役割](../backend/class-roles/index.md) に置く。
  - **`<Job>JobConfig`**：Job と Step を Bean で定義する `@Configuration`。
  - **`<Job>ItemReader`**：処理する行を読む ItemReader。
  - **`<Job>ItemWriter`**：chunk の 1 件ごとに CommandHandler を呼ぶ ItemWriter。
  - **`<Job>Tasklet`**：chunk にしない 1 単位の処理で CommandHandler を呼ぶ Tasklet。
- Job の名前は `<module>.<JobName>`（`ordering.CloseMonth`）にし、設計書の識別子にも使う。
- `presentation` には置かない。
  Job と Step の設定は chunk、skip、retry、partition で処理の進め方と再開の単位を決め、Controller のように要求を Command に変えるだけの薄い入口にならないためである。
  機能モジュールの外にも置かない。
  CommandHandler は各モジュールの内部の `application` にあり、外から呼ぶと Spring Modulith の `verify()` が止めるためである。
- ItemReader は、自分のモジュールの `<Feature>Queries` を呼んで読む。
  `<Feature>QueryService` の名前のある読み取り専用のトランザクションで、モジュールのロールとテナントが効く。
  Spring Batch の JDBC の reader は使わず、`infrastructure.persistence` に reader を置かない。
  JDBC の reader はアプリロールの接続で読み、業務のスキーマに権限がない。
  `infrastructure.persistence` の reader は、`@Transactional` を `application` の public メソッドだけに許す ADR-050 の規則に反する。
- ItemWriter と Tasklet は、1 件か 1 単位ごとに 1 つの CommandHandler だけを呼び、Repository、`TableWriter`、`DSLContext` を直接呼ばない。
- `*_pgm_cd` は CommandHandler の `handle` の束縛（ADR-051）で入るので、`PgmCdAspect` の pointcut は変えない。
  認証がないので、`*_by` には `*_pgm_cd` と同じ値が入る。

### スパンとテナント

- `*_tx_id` には、Spring Batch の Job と Step の observation が作るスパンの trace ID を使い、自前のスパンを始めない。
  Spring Boot の構成は、Bean の Job と Step にアプリの `ObservationRegistry` を渡すので、Job と Step は Bean で定義する。
- テナントの業務の表を書く Job は、Step を Spring Batch の partition（`Partitioner`）でテナントごとに分ける。
  partition の一覧は `tenancy` の `<Feature>Queries` から `m_tenant` のテナントを読んで作り、各 partition の ExecutionContext にテナントの `public_id` を置く。
- `shared.infrastructure.persistence` に Aspect を 1 つ置き、`<module>.batch` の ItemReader の `read`、ItemWriter の `write`、Tasklet の `execute` の呼び出しの間だけ、現在の Step の ExecutionContext のテナントを `TenantContext` に束縛する。
  形と順序は、リスナーの `TenantListenerAspect`（[ADR-105](ADR-105-bind-tenant-to-request-and-event-context.md)）と同じにする。
  テナントがない Step では、`IllegalStateException` で拒否する。
- 1 つのテナントの partition が失敗しても、ほかのテナントの partition は流れる。
  再開では、COMPLETED でない partition だけが流れる。
- partition は既定の `SyncTaskExecutor` で順に流す。
  並列に流すときは partition の `TaskExecutor` で行い、Step の中で DB への接続を並列にしない。

### オンラインとの排他

- 排他の要否は、ジョブとオンラインが、同じ時間帯に同じ表の同じ行を書くかで決める。
  行の版（`lock_no`）は行ごとに 1 つなので、列が違っても同じ行なら版が競合する。
- ジョブが読む行がオンラインでもう変わらない状態にあり、ジョブが自分のモジュールの表とイベントだけを書くなら、ロックも `REPEATABLE READ` も使わない。
- 集約を更新するなら、読んだ版と行の版を比べ（[PostgreSQLの排他制御](../database/postgresql-concurrency-control.md)）、競合した 1 件は Spring Batch の retry で読み直し、それでも競合すれば skip する。

## Consequences

### Positive

- 入口、再開、二重の起動の検知、実行の履歴を自作せず、Spring Batch と Spring Boot の公開された API と設定で作る。
  追いつきも、どの期間が COMPLETED かを JobRepository から読み、自作するのは期間を順に流す Runner だけになる。
- 1 つの期間が 1 つの JobInstance になり、どの期間がいつ、何件で、成功したかを JobRepository から読める。
- 起動元に依存しないので、ADR-045 の書き直しで起動元を決めても Job を変えない。
- トランザクションの境界が `handle` のままなので、ロール、テナント、`*_pgm_cd` の仕組みを変えない。

### Negative

- 1 件ごとに commit するので、chunk ごとに commit するより遅い。
  遅いジョブは、1 つの Command に複数の行をまとめて補う。
- Step の状態の保存と業務の書き込みが別のトランザクションになり、再開と skip で同じ行がもう一度渡る。
  すべての ItemWriter と Tasklet の `handle` を冪等にする必要がある。
- 追いつきの Runner と、テナントを束縛する Aspect の 2 つは自作する。
- 異常終了で STARTED のまま残った JobExecution は、次の起動を `JobExecutionAlreadyRunningException` で止める。
  運用者が Spring Batch 6 の `JobOperator` の `recover` で直す必要がある。
- メタデータの表の時刻は、業務の表と違って `timestamptz` でなく、JVM の既定のタイムゾーンの時刻になる。

### Neutral

- 最初のジョブと同じ変更で、次を足す。
  - 依存と、メタデータの表の changeset。
  - ItemWriter と Tasklet が Repository、`TableWriter`、`DSLContext` を使わないことと、`<module>.batch` の置き場所を確かめる ArchUnit の規則。
  - ADR-105 の `TenantContext.call` の許可先と ArchUnit の規則に、テナントを束縛する Aspect を足す。
  - `modulith` スキーマの表の一覧（[DB 接続](../database/connections.md)、[PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md)）にメタデータの表を足す。
  - 業務のモジュールが `tenancy` の `<Feature>Queries` を呼ぶ依存を、モジュールの依存の一覧に足す。
  - STARTED のまま残った JobExecution を直す Runbook。
- メタデータの表の保持期間と削除は、最初のジョブを作るときに決める。
- 規則の本文は [バッチ](../batch/index.md) に置く。

## Alternatives Considered

### 選択肢1: Spring Batch を使わず、自前の入口と「最後に処理した期間」の列で追いつく

- **Description**：#314 の最初の結論。`EventPublicationResubmitOnce` と同じ形の Runner が環境変数を検査し、業務の表に持つ「最後に処理した期間」の次から追いつく。二重の起動は業務のキーの一意制約で止め、実行の記録の表を持たない。
- **Pros**：依存が増えない。メタデータの表がない。
- **Cons**：入口、引数の検査、追いつき、再開の位置、実行の履歴を自作し、テストする必要がある。Spring Batch を見送った理由の「再開の状態を JobRepository と業務の行の 2 か所に持つ」は、process indicator の形で成り立たない。

### 選択肢2: `ResourcelessJobRepository` を使う

- **Description**：Spring Batch 6 の既定のまま、メタデータの表を持たない。
- **Pros**：表と changeset が要らない。
- **Cons**：Spring Batch のリファレンスは、再開を要しない 1 回だけのジョブ向けで、並行の環境で使えないとしている。再開、二重の起動の検知、実行の履歴を失う。

### 選択肢3: Step のトランザクションにモジュールの名前を付け、`handle` を chunk のトランザクションに加える

- **Description**：Step の `transactionManager` にアプリのトランザクションマネージャーを、`transactionAttribute` にモジュールの名前を渡す。
- **Pros**：chunk の大きさの分だけ commit がまとまり、Step の状態の保存と業務の書き込みが同じトランザクションになる。
- **Cons**：Step の状態の保存がモジュールのロールで流れ、`permission denied for table batch_step_execution_context` で失敗した（Context の確かめ）。動かすには、メタデータの表の権限をすべてのモジュールのロールに与え、テナントを Step の外で束縛する必要があり、ADR-094 のロールの分離と ADR-050 のトランザクションの境界に例外ができる。

### 選択肢4: メタデータの表を、どのモジュールにも属さない専用の `batch` スキーマに置く

- **Description**：`modulith` と別のスキーマを作り、アプリロールに権限を与える。
- **Pros**：Spring Batch の表と Spring Modulith の表が分かれる。
- **Cons**：ADR-094 はアプリロールが触れる表を `modulith` スキーマに限り、ADR-107 は RLS の外に置くスキーマを `modulith` と `tenancy` に限る。2 つの ADR、changeset の検査、カタログのテストの除外を変える必要がある。ADR-111 はセッションの表を同じ理由で `modulith` に置いた。

### 選択肢5: 起動した日を 1 つの JobInstance にし、期間を業務の行で追う

- **Description**：識別の引数を起動した日にし、Job の中で業務の行から未処理の期間を求めて流す。Spring Boot の Runner をそのまま使える。
- **Pros**：追いつきの Runner を自作しない。
- **Cons**：JobRepository の履歴が期間ごとにならず、どの期間がいつ成功したかを業務の行から読む必要がある。同じ日の 2 回目の起動が `JobInstanceAlreadyCompleteException` で失敗になる。

### 選択肢6: 起動元が期間を渡す

- **Description**：Spring Boot の Runner のまま、起動元が `period` を渡す。
- **Pros**：自作がない。
- **Cons**：追いつきが起動元の責務になり、起動元に依存しない Job にならない。

### 選択肢7: Step Functions の Map でテナントを回す

- **Description**：起動元のワークフローがテナントごとにタスクを起動する。
- **Pros**：テナントごとに起動と再試行を分けられる。
- **Cons**：起動元を決めることになり、ADR-045 と食い違う。partition で同じことができる。

### 選択肢8: 業務日付管理テーブルと日替わりのジョブで期間を決める

- **Description**：業務日付を表に持ち、日替わりのジョブが進め、各ジョブは業務日付から期間を決める。
- **Pros**：テストで業務日付を固定できる。
- **Cons**：期間は `Clock` と業務タイムゾーンから求められ、日替わりのジョブ同士に依存ができる。業務日付を持つかは、これまでどおり [業務日付](../database/postgresql-temporal-data.md#業務日付) の条件で決める。

### 選択肢9: ShedLock だけで二重の起動を止める

- **Description**：業務のバッチも ADR-079 と同じく ShedLock のロックを取る。
- **Pros**：再投入と同じ仕組みで済む。
- **Cons**：ShedLock は `lockAtMostFor` を超えた実行で複数のプロセスがロックを持ちうる。JobRepository が同じ JobInstance の二重の実行を止めるので、重ねる理由がない。

### 選択肢10: オンラインが `NOWAIT` で悲観的ロックを取り、常にバッチで上書きする

- **Description**：原文の「常にバッチで上書き」の形。
- **Pros**：バッチが楽観的ロックの失敗を扱わずに済む。
- **Cons**：このリポジトリのオンラインは読んだ版と行の版を比べる楽観的ロックで（[ADR-054](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)）、HTTP の要求と応答をまたいで行のロックを保てない。

## References

- [#314: バッチの方式と、バッチの設計の docs](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/314)
- [#558: バッチの docs と ADR](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/558)
- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [ADR-079: 失敗したイベント出版を ShedLock で定期に再投入する](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)
- [ADR-111: セッションを PostgreSQL に保存する](ADR-111-store-sessions-in-postgresql-until-user-growth-needs-redis.md)
- [バッチ](../batch/index.md)
- Spring Batch 6.0, The Domain Language of Batch: <https://docs.spring.io/spring-batch/reference/6.0/domain.html>
- Spring Batch 6.0, Configuring a JobRepository: <https://docs.spring.io/spring-batch/reference/6.0/job/configuring-repository.html>
- Spring Batch 6.0, Preventing State Persistence: <https://docs.spring.io/spring-batch/reference/6.0/readers-and-writers/process-indicator.html>
- Spring Batch 6.0, What's new in Spring Batch 6: <https://docs.spring.io/spring-batch/reference/6.0/whatsnew.html>
- Spring Batch 6.0, Scaling and Parallel Processing: <https://docs.spring.io/spring-batch/reference/6.0/scalability.html>
- Spring Batch 6.0.6 のソース（`SimpleJobOperator`、`ChunkOrientedStep`、`TaskletStep`、`SimpleStepExecutionSplitter`、`BatchObservabilityBeanPostProcessor`）: <https://github.com/spring-projects/spring-batch/tree/v6.0.6/spring-batch-core>
- Spring Boot 4.1, Spring Batch: <https://docs.spring.io/spring-boot/4.1/reference/io/spring-batch.html>
- Spring Boot 4.1, Batch Applications: <https://docs.spring.io/spring-boot/4.1/how-to/batch.html>
- Spring Boot 4.1.2 のソース（`BatchJobLauncherAutoConfiguration`、`JobLauncherApplicationRunner`、`JobExecutionExitCodeGenerator`）: <https://github.com/spring-projects/spring-boot/tree/v4.1.2/module/spring-boot-batch>
