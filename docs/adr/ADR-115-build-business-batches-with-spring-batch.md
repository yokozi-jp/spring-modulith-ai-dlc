---
type: ADR
title: 'ADR-115: 業務のバッチを Spring Batch の Job で作り、1 つの期間を 1 つの JobInstance にして毎日追いつく'
description: 締め、集計、ファイルの配信と集信のような業務のバッチを Spring Batch 6 の Job にし、JobRepository を modulith スキーマに置き、Step のトランザクションを持たずに CommandHandler の handle に任せる決定。
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
- Step に `ModuleRoleTransactionManager` と、モジュールの名前を付けた `transactionAttribute` を渡した。
  Step は `permission denied for table batch_step_execution_context` で FAILED になった。
  `ChunkOrientedStep` は、chunk のトランザクションの中で Step の状態を JobRepository に保存する（Spring Batch 6.0.6 のソースの `saveState`）。
  その保存が `ordering_module` のロールで流れ、メタデータの表に権限がなかった。
- Spring Boot の構成と同じく Job と Step に `ObservationRegistry` を渡すと、ItemWriter の中に現在のスパンがあった。
  1 回の JobExecution の中の書き込みは、同じ trace ID になった。

Proposed の間に Decision を書き直した。
skip、終了コード、ABANDONED の期間、JobExecution のない JobInstance、Runner の終わり方について、上の版のソースで確かめた振る舞いが最初の Decision と合わなかったためである。

## Decision

規則の本文は [バッチ](../batch/index.md) に置き、ここには決定と理由だけを書く。
対象にする処理の範囲は [ジョブの入口と起動](../batch/job-entry.md#対象) に従う。

- 業務のバッチを Spring Batch の Job にし、Spring Boot の自動構成で動かす。
  理由は、入口、再開、二重の起動の検知、実行の履歴を自作せずに済み、Spring Batch の構成を自分で宣言すると `spring.batch.*` の設定と自動構成が退くためである（選択肢1）。
- JobRepository は Spring Boot が構成する JDBC の JobRepository にし、メタデータの表を `modulith` スキーマに置いてアプリロールにだけ権限を与える。
  理由は、既定の `ResourcelessJobRepository` が再開と並行の実行に対応せず（選択肢2）、JobRepository のトランザクションは名前が機能モジュールのパッケージで始まらないので `ModuleRoles.roleFor` がモジュールを返さず、アプリロールのまま RLS の外の `modulith` に書けるためである（[ADR-094](ADR-094-switch-to-module-database-roles.md)、[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)、選択肢4）。
- 同じイメージを Job の名前を渡して 1 回だけ起動し、`shared.batch` の追いつきの Runner が Job を流して、どの終わり方でも `System.exit` で終了コードを返す。
  理由は、Spring Boot が `SpringApplication.exit` を自分で呼ばず、`JobExecutionExitCodeGenerator` が `JobLauncherApplicationRunner.execute` の出すイベントだけを見るので、それを継承した Runner が `super.execute` を呼ぶ必要があり、`TaskExecutorJobLauncher` が `job.execute` の例外を投げ直すので、Runner が受けなければ JVM が終わらないことがあるためである。
  起動元は [ADR-045](ADR-045-remove-aws-docs-and-production-cd-example.md) の AWS の規約の書き直しで決め、Job はどの起動元から何度起動されても正しく終わるように作る。
- 期間で区切る Job は、期間の初日を識別の引数にして 1 つの期間を 1 つの JobInstance にし、毎日起動して Runner が済んでいない期間を古い順に流す。
  理由は、期間ごとの成否を JobRepository から読め（選択肢5）、追いつきを起動元に持たせずに済む（選択肢6）ためである。
  `JobParametersIncrementer` は使わない。Spring Batch 6.0.6 の `SimpleJobOperator.start` は、incrementer を持つ Job では渡した引数を捨てて前の JobExecution から次の引数を作り、失敗した期間を飛ばすためである。
  期間で区切らない Job は、処理する単位の業務のキー（集信するファイルのオブジェクトのキー）を識別の引数にする。識別の引数が同じ起動は同じ JobInstance になり、COMPLETED の後の起動が `JobInstanceAlreadyCompleteException` で止まるためである。
- Runner は、最後の JobExecution が ABANDONED の JobInstance を済んだ期間として数え、運用者が期間を Job で流さないと決めた印にする。
  理由は、Spring Batch が ABANDONED の JobInstance を COMPLETED と同じく起動しないので、数えなければ Runner が毎日同じ期間で止まるためである。
- DB から読む reader は process indicator にし、skip を使わない。
  理由は、Step の状態の保存と業務の書き込みが別のトランザクションなので保存した位置と業務の行の状態がずれ、skip した行は Step が COMPLETED で終わった後に読み直す処理がないためである。
- Step はトランザクションを持たず、ItemWriter と Tasklet が呼ぶ CommandHandler の `handle` を冪等にして 1 件か 1 単位ごとに commit する。
  理由は、Step にモジュールの名前のトランザクションを渡すと Step の状態の保存がモジュールのロールで流れて権限で失敗し（Context の確かめ、選択肢3）、`handle` に任せればロール、テナント、`*_pgm_cd`（[ADR-051](ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)）の仕組みを変えずに済むためである。
- 二重の起動は JobRepository に任せて ShedLock を使わず、正しさは `handle` の冪等性で守る（選択肢9）。
  理由は、Spring Batch 6.0.6 の `TaskExecutorJobLauncher` が前の JobExecution の確認と新しい JobExecution の作成を別のトランザクションで行い、同時の 2 本を必ずは止めないためである。
- 機能モジュールに 5 つ目の層 `batch` を足し、Job の設定、ItemReader、ItemWriter、Tasklet を置く。追いつきの Runner、テナントを束縛する Aspect、`JobPeriodRule` は `shared.batch` に置く。
  理由は、内部の `application` の CommandHandler を機能モジュールの外から呼ぶと Spring Modulith の `verify()` が止め、Job と Step の設定は chunk、retry、partition で再開の単位を決めるので薄い入口の `presentation` に合わず（選択肢11）、Runner と Aspect は業務の概念を持たずにすべての Job が共有するためである。
  [ADR-002](ADR-002-package-by-feature-onion-architecture.md) の 4 つの層をこの決定で改める。
- ItemReader は自分のモジュールの `<Feature>Queries` で読む。
  理由は、Spring Batch の JDBC の reader はアプリロールの接続で読むので業務のスキーマに権限がなく、`infrastructure.persistence` に reader を置くと `@Transactional` を `application` の public メソッドだけに許す ADR-050 に反するためである。
- テナントの業務の表を書く Job は、Step をテナントの `public_id` を名前にした partition に分け、`shared.batch` の Aspect が partition のテナントを束縛する（[ADR-105](ADR-105-bind-tenant-to-request-and-event-context.md)）。
  理由は、Spring Batch 6.0.6 の `SimpleStepExecutionSplitter` が再開で同じ名前の partition の前回の ExecutionContext を使うので、連番の名前ではテナントが増減すると別のテナントの状態を引き継ぐためである。
- Job と Step は Bean で定義し、`*_tx_id` に Spring Batch の observation のスパンの trace ID を使う。
  理由は、Spring Boot がアプリの `ObservationRegistry` を渡すのは Bean の Job と Step だけだからである。
- オンラインと同じ行を書く Job は、読んだ版と行の版を比べ、競合は retry で読み直す。
  理由は、オンラインが HTTP の要求と応答をまたいで行のロックを保てない楽観的ロックだからである（選択肢10）。

## Consequences

### Positive

- 入口、再開、二重の起動の検知、実行の履歴を自作せず、Spring Batch と Spring Boot の公開された API と設定で作る。
  自作するのは期間を順に流す Runner と、テナントを束縛する Aspect だけになる。
- 1 つの期間が 1 つの JobInstance になり、どの期間がいつ、何件で、成功したかを JobRepository から読める。
- 起動元に依存しないので、ADR-045 の書き直しで起動元を決めても Job を変えない。
- トランザクションの境界が `handle` のままなので、ロール、テナント、`*_pgm_cd` の仕組みを変えない。

### Negative

- 1 件ごとに commit するので、chunk ごとに commit するより遅い。
  遅いジョブは、1 つの Command に複数の行をまとめて補う。
- すべての ItemWriter と Tasklet の `handle` を冪等にする必要がある。
- 追いつきの Runner は、Spring Boot の自動構成のパッケージにある `JobLauncherApplicationRunner` を継承する。
  `execute` と自動構成の条件は Spring Boot が互換を約束した拡張点ではなく、版を上げると変わることがある。
  Job の結合テストで、終了コードと Runner の差し替えを固定する。
- skip を使わないので、1 件の業務上の失敗でその期間とそれより後の期間が止まり、運用者がデータパッチで直すまで流れない。
- メタデータの表の時刻は、業務の表と違って `timestamptz` でなく、JVM の既定のタイムゾーンの時刻になる。
  Spring Batch が `LocalDateTime.now()` で書くためで、JVM を UTC にする [ADR-006](ADR-006-utc-instant-absolute-time-policy.md) を Job の起動の前提にする。
- Spring Batch の `TaskExecutorJobLauncher` は JobInstance と JobExecution を別のトランザクションで作るので、その間で落ちると JobExecution のない JobInstance が残り、運用者が消すまでその期間が止まる。

### Neutral

- Job を作るときに足す依存、changeset、ArchUnit の規則、表の一覧の更新、`tenancy` の全テナントを返すメソッド、Runbook の起動の手段、メタデータの表の保持期間は [#667](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/667) で扱う。
- Spring Batch と Spring Boot は、起動のときに JobParameters をすべて INFO のログに出す。
  [処理ごとに記録するログ](../observability/log-output-points.md#一括処理) の allowlist との合わせ方は [#669](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/669) で扱う。
- 未起動と遅延を検知するメトリクスは、この ADR で決めない（[#668](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/668)）。

## Alternatives Considered

### 選択肢1: Spring Batch を使わず、自前の入口と「最後に処理した期間」の列で追いつく

- **Description**：#314 の最初の結論。`EventPublicationResubmitOnce` と同じ形の Runner が環境変数を検査し、業務の表に持つ「最後に処理した期間」の次から追いつく。二重の起動は業務のキーの一意制約で止め、実行の記録の表を持たない。
- **Pros**：依存が増えない。メタデータの表がない。
- **Cons**：入口、引数の検査、追いつき、再開の位置、実行の履歴を自作し、テストする必要がある。
  #314 の最初の結論は、Spring Batch を見送った理由に「再開の状態を JobRepository と業務の行の 2 か所に持つ」ことを挙げていた。
  process indicator の reader は位置を保存せず、再開の状態を業務の行だけが持つので、この理由は成り立たない。

### 選択肢2: `ResourcelessJobRepository` を使う

- **Description**：Spring Batch 6 の既定のまま、メタデータの表を持たない。
- **Pros**：表と changeset が要らない。
- **Cons**：Spring Batch のリファレンスは、再開を要しない 1 回だけのジョブ向けで、並行の環境で使えないとしている。再開、二重の起動の検知、実行の履歴を失う。

### 選択肢3: Step のトランザクションにモジュールの名前を付け、`handle` を chunk のトランザクションに加える

- **Description**：Step の `transactionManager` にアプリのトランザクションマネージャーを、`transactionAttribute` にモジュールの名前を渡す。
- **Pros**：chunk の大きさの分だけ commit がまとまり、Step の状態の保存と業務の書き込みが同じトランザクションになる。
- **Cons**：Step の状態の保存がモジュールのロールで流れ、`permission denied for table batch_step_execution_context` で失敗した（Context の確かめ）。
  動かすには、メタデータの表の権限をすべてのモジュールのロールに与え、テナントを Step の外で束縛する必要があり、ADR-094 のロールの分離と ADR-050 のトランザクションの境界に例外ができる。

### 選択肢4: メタデータの表を、どのモジュールにも属さない専用の `batch` スキーマに置く

- **Description**：`modulith` と別のスキーマを作り、アプリロールに権限を与える。
- **Pros**：Spring Batch の表と Spring Modulith の表が分かれる。
- **Cons**：ADR-094 はアプリロールが触れる表を `modulith` スキーマに限り、ADR-107 は RLS の外に置くスキーマを `modulith` と `tenancy` に限る。
  2 つの ADR、changeset の検査、カタログのテストの除外を変える必要がある。ADR-111 はセッションの表を同じ理由で `modulith` に置いた。

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
- **Cons**：期間は `Clock` と業務タイムゾーンから求められ、日替わりのジョブ同士に依存ができる。業務日付を持つかは [業務日付](../database/postgresql-temporal-data.md#業務日付) の条件で決める。

### 選択肢9: ShedLock だけで二重の起動を止める

- **Description**：業務のバッチも ADR-079 と同じく ShedLock のロックを取る。
- **Pros**：再投入と同じ仕組みで済む。
- **Cons**：ShedLock は `lockAtMostFor` を超えた実行で複数のプロセスがロックを持ちうる。JobRepository も二重の起動を多くの場合止め、どちらも正しさを `handle` の冪等性に頼るので、重ねる理由がない。

### 選択肢10: オンラインが `NOWAIT` で悲観的ロックを取り、常にバッチで上書きする

- **Description**：取り込み元のフューチャーの「バッチ設計ガイドライン」（[ADR-040](ADR-040-import-future-architecture-guidelines.md)）が示す、オンラインのロックの失敗を返し、バッチは常に上書きする形。
- **Pros**：バッチが楽観的ロックの失敗を扱わずに済む。
- **Cons**：このリポジトリのオンラインは読んだ版と行の版を比べる楽観的ロックで（[ADR-054](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)）、HTTP の要求と応答をまたいで行のロックを保てない。

### 選択肢11: ItemWriter と Tasklet を `application` に置く

- **Description**：Listener と同じく、CommandHandler を呼ぶ入口を `application` に置き、Job の設定と ItemReader も同じパッケージに置く。
- **Pros**：層を足さない。CommandHandler を同じパッケージから呼べる。
- **Cons**：Listener は `@ApplicationModuleListener` の `on` が自分のトランザクションを開くので `application` に置くが、ItemWriter と Tasklet はトランザクションを持たない。
  `application` が Spring Batch の型に依存し、`shared.batch` を使ってよい層と、Domain と Infrastructure に依存しない入口の層を、パッケージで分けて検査できない。

## References

- [#314: バッチの方式と、バッチの設計の docs](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/314)
- [#558: バッチの docs と ADR](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/558)
- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [ADR-002: package by feature とオニオンアーキテクチャ](ADR-002-package-by-feature-onion-architecture.md)
- [ADR-079: 失敗したイベント出版を ShedLock で定期に再投入する](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)
- [ADR-111: セッションを PostgreSQL に保存する](ADR-111-store-sessions-in-postgresql-until-user-growth-needs-redis.md)
- [バッチ](../batch/index.md)
- Spring Batch 6.0, The Domain Language of Batch: <https://docs.spring.io/spring-batch/reference/6.0/domain.html>
- Spring Batch 6.0, Configuring a JobRepository: <https://docs.spring.io/spring-batch/reference/6.0/job/configuring-repository.html>
- Spring Batch 6.0, Preventing State Persistence: <https://docs.spring.io/spring-batch/reference/6.0/readers-and-writers/process-indicator.html>
- Spring Batch 6.0, What's new in Spring Batch 6: <https://docs.spring.io/spring-batch/reference/6.0/whatsnew.html>
- Spring Batch 6.0, Scaling and Parallel Processing: <https://docs.spring.io/spring-batch/reference/6.0/scalability.html>
- Spring Batch 6.0.6 のソース（`SimpleJobOperator`、`TaskExecutorJobLauncher`、`ChunkOrientedStep`、`TaskletStep`、`SimpleStepExecutionSplitter`、`BatchObservabilityBeanPostProcessor`）: <https://github.com/spring-projects/spring-batch/tree/v6.0.6/spring-batch-core>
- Spring Boot 4.1, Spring Batch: <https://docs.spring.io/spring-boot/4.1/reference/io/spring-batch.html>
- Spring Boot 4.1, Batch Applications: <https://docs.spring.io/spring-boot/4.1/how-to/batch.html>
- Spring Boot 4.1.2 のソース（`BatchJobLauncherAutoConfiguration`、`JobLauncherApplicationRunner`、`JobExecutionExitCodeGenerator`、`SpringApplication`）: <https://github.com/spring-projects/spring-boot/tree/v4.1.2/module/spring-boot-batch>
