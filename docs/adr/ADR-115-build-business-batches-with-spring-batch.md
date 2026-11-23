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

Proposed の間に、[#656](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/656) のレビューで Decision を書き直した。
レビューは、skip した行を読み直す処理がないこと、FAILED でも終了コードが 0 になりうること、ABANDONED の期間で追いつきが止まることなどを、上の版のソースで示した。
あわせて、規則の本文を [バッチ](../batch/index.md) に移し、この ADR には決定と理由だけを残した。

## Decision

### 入口と終了コード

- 短い保守の処理（[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md) の再投入、[#542](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/542) の archive の削除）は、これまでどおり `@Scheduled` で動かす。
- 業務のバッチ（締め、集計、ファイルの配信と集信）は、Spring Batch の Job にし、Spring Boot の自動構成で動かす。
  Spring Batch の構成を自分で宣言すると、`spring.batch.*` の設定と自動構成が退くためである。
- 同じイメージを、Job の名前を渡して 1 回だけ起動する。
  形は、手での 1 回の再投入（ADR-079）と同じにする。
- 起動元（EventBridge Scheduler、Step Functions など）は決めず、[ADR-045](ADR-045-remove-aws-docs-and-production-cd-example.md) の AWS の規約の書き直しで決める。
  Job は、どの起動元から何度起動されても、下の JobInstance と再開で正しく終わるように作る。
- Job は、`shared.batch` の 1 つの追いつきの Runner が起動し、終わったら Runner が JVM を終えて `BatchStatus` の序数を終了コードにする。
  Spring Boot は `SpringApplication.exit` を自分で呼ばず、`JobExecutionExitCodeGenerator` は `JobLauncherApplicationRunner.execute` が出すイベントだけから終了コードを決めるためである。
  Runner は `JobLauncherApplicationRunner` を継承し、Job を流すたびに `super.execute` を呼ぶ。
- 通常の起動では Spring Boot の Runner も作らせない。
  Spring Boot の Runner は、Runner の Bean がほかになく、`spring.batch.job.enabled` が `false` でなければ作られ、Job が 2 つ以上あると名前なしの起動で失敗するためである。

### JobRepository

- Spring Boot が構成する JDBC の JobRepository を使い、メタデータの表を `modulith` スキーマに置いて、アプリロールにだけ権限を与える。
  Spring Batch 6 の既定の `ResourcelessJobRepository` は、再開と並行の実行に対応しない（選択肢2）。
  JobRepository のトランザクションの名前は機能モジュールのパッケージで始まらないので、`ModuleRoles.roleFor` がモジュールを返さず、アプリロールのまま動く（[ADR-094](ADR-094-switch-to-module-database-roles.md)）。
  `modulith` スキーマは RLS の外にあり（[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）、テナントを設定せずに書ける。
- 実行の履歴は JobRepository の表で持ち、自前の実行の記録の表を作らない。
- メタデータの表の時刻は、業務の判断に使わない。
  Spring Batch は JVM の既定のタイムゾーンの `LocalDateTime.now()` で書き、`timestamptz` でないためである。
  JVM を UTC にする [ADR-006](ADR-006-utc-instant-absolute-time-policy.md) の決定を、Job の起動の前提にする。

### 期間と追いつき

- 期間で区切る Job は、期間の初日を識別の引数にし、1 つの期間を 1 つの JobInstance にする。
  どの期間がいつ、何件で、成功したかを JobRepository から読めるためである（選択肢5）。
- 月次の Job も毎日起動し、Runner が済んでいない期間を古い順に流し、COMPLETED にならない期間で止める。
  追いつきを起動元に持たせないためである（選択肢6）。
- Runner は、COMPLETED と ABANDONED の JobInstance を済んだ期間として扱う。
  Spring Batch は ABANDONED の JobInstance を COMPLETED と同じく起動しないので、済んだ期間として扱わないと Runner が毎日同じ期間で止まるためである。
  ABANDONED は、運用者がその期間を Job で流さないと決めた印にする。
- 期間が終わってから決めた時間を待って流し、期間が締まった後にその期間の行が増えない列で行を選ぶ。
  COMPLETED の JobInstance は二度と流れず、次の期間の Job は期間で行を絞るので、後から入った行を拾う処理がないためである。
- `JobParametersIncrementer` は使わない。
  Spring Batch 6.0.6 の `SimpleJobOperator.start` は、incrementer を持つ Job では渡した引数を捨て、前の JobExecution の引数から次の引数を作るので、失敗した期間を飛ばす。
- 期間の境界は Java で業務タイムゾーンから求めて SQL に引数で渡す（[ADR-114](ADR-114-pass-calendar-dates-as-full-date-and-bind-boundaries.md)）。

### 再開と失敗

- DB から読む reader は process indicator にし、読んだ位置を保存しない。
  Step の状態の保存と業務の書き込みが別のトランザクションなので（下の「トランザクションとロール」）、保存した位置と業務の行の状態がずれるためである。
- 業務のバッチでは skip を使わない。
  retry の上限を超えた競合と、ほかの例外は Step を失敗させ、Job を FAILED にして次の起動で再開する。
  skip した行は未処理のまま残るが、skip のある Step も COMPLETED で終わり、期間の後に入った行と同じく、その行を読み直す処理がないためである。
  同じ理由で、ロックを取れない行を `SKIP LOCKED` で飛ばさない。
- 同じ行で失敗し続ける期間、UNKNOWN の期間、異常終了で STARTED のまま残った期間は、運用者が直す。
  手順の Runbook は、最初の Job と同じ変更で作る（[#667](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/667)）。

### 二重の起動

- 同じ JobInstance の二重の起動は、多くの場合 JobRepository が止める。
  業務のバッチには ShedLock を使わない（選択肢9）。
- それでも、正しさは `handle` の冪等性で守る（[#551](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/551) と同じ）。
  Spring Batch 6.0.6 の `TaskExecutorJobLauncher` は、前の JobExecution の確認と新しい JobExecution の作成を別のトランザクションで行い、同時の 2 本を必ずは止めないためである。

### トランザクションとロール

- Step はトランザクションを持たず、ItemWriter と Tasklet が呼ぶ CommandHandler の `handle` が 1 件か 1 単位ごとにトランザクションを開く。
  [ADR-050](ADR-050-define-backend-class-roles-and-naming.md) の「トランザクションの境界は `handle`」と、ADR-094 のロールの切り替えをそのまま使うためである（選択肢3）。
- `handle` を冪等にする。
  1 件ごとに commit するので、落ちた後の再開と retry で、同じ行が `handle` にもう一度渡るためである。

### 置き場所

- Job を持つ機能モジュールの `<module>.batch` に、Job の設定、ItemReader、ItemWriter、Tasklet の役割を置く。
  `presentation` に置かないのは、Job と Step の設定が chunk、retry、partition で処理の進め方と再開の単位を決め、薄い入口にならないためである。
  機能モジュールの外に置かないのは、内部の `application` の CommandHandler を外から呼ぶと、Spring Modulith の `verify()` が止めるためである。
- 追いつきの Runner とテナントを束縛する Aspect は、`shared.batch` に 1 つずつ置く。
  業務の概念を持たず、すべての Job に共通で、Job の設定が使う期間の規則の型を公開する必要があるためである。
  `shared.infrastructure.persistence` は、他のモジュールの `infrastructure.persistence` だけが使える。
- ItemReader は、自分のモジュールの `<Feature>Queries` で読む。
  Spring Batch の JDBC の reader はアプリロールの接続で読み、業務のスキーマに権限がない。
  `infrastructure.persistence` の reader は、`@Transactional` を `application` の public メソッドだけに許す ADR-050 に反する。
- ItemWriter と Tasklet は 1 つの CommandHandler だけを呼ぶ。
  `*_pgm_cd` は `handle` の束縛（ADR-051）で入るので、`PgmCdAspect` の pointcut を変えない。

### スパンとテナント

- `*_tx_id` には、Spring Batch の Job と Step の observation が作るスパンの trace ID を使う。
  Spring Boot がアプリの `ObservationRegistry` を渡すのは Bean の Job と Step だけなので、Job と Step は Bean で定義する。
- テナントの業務の表を書く Job は、Step をテナントごとの partition に分け、partition の名前をテナントの `public_id` にする。
  Spring Batch 6.0.6 の `SimpleStepExecutionSplitter` は、再開で同じ名前の partition の前回の ExecutionContext を使うので、連番の名前ではテナントが増減すると別のテナントの状態を引き継ぐためである。
- partition のテナントは `shared.batch` の Aspect が束縛し、形と順序はリスナーの `TenantListenerAspect`（[ADR-105](ADR-105-bind-tenant-to-request-and-event-context.md)）と同じにする。
- Step の中で DB への接続を並列にせず、並列は partition で行う。
  スレッドごとにトランザクションが分かれ、Job の成否と再開の位置に一致しなくなるためである。

### オンラインとの排他

- 排他の要否は、ジョブとオンラインが、同じ時間帯に同じ表の同じ行を書くかで決める。
  行の版（`lock_no`）は行ごとに 1 つなので、列が違っても同じ行なら版が競合する。
- 集約を更新するなら、読んだ版と行の版を比べ（[PostgreSQLの排他制御](../database/postgresql-concurrency-control.md)）、競合は retry で読み直す。

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
  最初の Job の結合テストで、終了コードと Runner の差し替えを固定する。
- skip を使わないので、1 件の業務上の失敗でその期間とそれより後の期間が止まり、運用者がデータパッチで直すまで流れない。
- メタデータの表の時刻は、業務の表と違って `timestamptz` でなく、JVM の既定のタイムゾーンの時刻になる。

### Neutral

- 最初の Job と同じ変更で足す依存、changeset、ArchUnit の規則、表の一覧の更新、Runbook は [#667](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/667) で扱う。
- `tenancy` の `<Feature>Queries` に、全テナントの一覧を返すメソッドが要る。
  ADR-107 の時点では、全テナントを読むコードがない（ADR-107 の選択肢6）。
- Spring Batch と Spring Boot は、起動のときに JobParameters をすべて INFO のログに出す。
  [処理ごとに記録するログ](../observability/log-output-points.md#一括処理) は allowlist の値だけを出すとしており、合わせ方は [#669](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/669) で決める。
- 未起動と遅延を検知するメトリクスは、この ADR で決めない（[#668](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/668)）。
- メタデータの表の保持期間と削除は、最初のジョブを作るときに決める。

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
- **Cons**：期間は `Clock` と業務タイムゾーンから求められ、日替わりのジョブ同士に依存ができる。業務日付を持つかは、これまでどおり [業務日付](../database/postgresql-temporal-data.md#業務日付) の条件で決める。

### 選択肢9: ShedLock だけで二重の起動を止める

- **Description**：業務のバッチも ADR-079 と同じく ShedLock のロックを取る。
- **Pros**：再投入と同じ仕組みで済む。
- **Cons**：ShedLock は `lockAtMostFor` を超えた実行で複数のプロセスがロックを持ちうる。JobRepository も二重の起動を多くの場合止め、どちらも正しさを `handle` の冪等性に頼るので、重ねる理由がない。

### 選択肢10: オンラインが `NOWAIT` で悲観的ロックを取り、常にバッチで上書きする

- **Description**：取り込み元のフューチャーの「バッチ設計ガイドライン」（[ADR-040](ADR-040-import-future-architecture-guidelines.md)）が示す、オンラインのロックの失敗を返し、バッチは常に上書きする形。
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
- Spring Batch 6.0.6 のソース（`SimpleJobOperator`、`TaskExecutorJobLauncher`、`ChunkOrientedStep`、`TaskletStep`、`SimpleStepExecutionSplitter`、`BatchObservabilityBeanPostProcessor`）: <https://github.com/spring-projects/spring-batch/tree/v6.0.6/spring-batch-core>
- Spring Boot 4.1, Spring Batch: <https://docs.spring.io/spring-boot/4.1/reference/io/spring-batch.html>
- Spring Boot 4.1, Batch Applications: <https://docs.spring.io/spring-boot/4.1/how-to/batch.html>
- Spring Boot 4.1.2 のソース（`BatchJobLauncherAutoConfiguration`、`JobLauncherApplicationRunner`、`JobExecutionExitCodeGenerator`、`SpringApplication`）: <https://github.com/spring-projects/spring-boot/tree/v4.1.2/module/spring-boot-batch>
