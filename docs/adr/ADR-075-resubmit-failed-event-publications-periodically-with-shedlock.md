---
type: ADR
title: 'ADR-075: 失敗したイベント出版を、advisory lock で 1 つのインスタンスに絞った定期のジョブで再投入する'
description: 失敗したイベント出版の自動の回復を、起動時の再配信ではなく、PostgreSQL の advisory lock で排他し、待ち時間と回数の上限で絞った定期のジョブで行う決定。
tags: [adr, integration, async, spring-modulith, resilience, postgresql]
---

# ADR-075: 失敗したイベント出版を、advisory lock で 1 つのインスタンスに絞った定期のジョブで再投入する

## Status

Proposed

## Date

2026-10-10

## Context

リスナーが失敗したイベント出版は、Spring Modulith のレジストリに `FAILED` で残る。
main では、運用者が `resubmit-once` の profile でアプリを起動して再投入するまで、二度と処理されない。
決済代行の数分の障害でも、確定済みで未払いの注文が人の対応まで残る（ADR-050、ADR-072）。

起動時の自動再配信（`republish-outstanding-events-on-restart`）は、複数のインスタンスが同時に同じ出版を再配信するため使わない（ADR-001）。
定期のジョブにしても、`@Scheduled` はインスタンスごとに動く。
Spring Modulith 2.1.1 の `markResubmitted` の条件は `STATUS != 'RESUBMITTED'` だけであり、先の再投入でリスナーが `PROCESSING` や `COMPLETED` に進めた行は二つ目の更新でも条件を満たすため、排他にならない。

Spring Modulith 2.1.1 の JDBC の V2 のスキーマは、状態、試行の回数（`completion_attempts`）、最後の再投入の時刻（`last_resubmission_date`）を持ち、main の Liquibase の changeset はこれらの列を作ってある。
`FailedEventPublications.resubmit(ResubmissionOptions)` は、`FAILED` の出版を最小の経過時間と条件の関数で絞って再投入できる。
ただし、件数の上限は条件の関数より先に SQL の `LIMIT` で掛かり、`resubmit` は再投入した件数を返さない。
最小の経過時間は、2.1.1 の SQL の演算子の優先順位のため `FAILED` の行に効かない。

ロックを持ったまま再投入するには 1 本の接続を固定する必要があるが、業務テーブルの書き込みを `TableWriter` に集める規則（H1、ADR-054）は、接続を直接扱う呼び出しを 3 クラスにだけ許している。

Staleness Monitor は落ちたまま残った出版を `FAILED` に戻せるが、`publication_date` から測るため、古い出版を再投入した直後にも `FAILED` に戻すことがある。

排他の仕組みとして使えるものは、backend の依存の中では PostgreSQL だけである。

## Decision

失敗したイベント出版を、定期のジョブで自動で再投入する。

- ジョブは `shared.infrastructure.persistence` の `EventPublicationResubmitter` に置き、`@Scheduled` の固定の遅延で動かす。
  `event-publication.resubmission.enabled` が偽なら、定期の実行も Staleness Monitor も登録しない。
  Spring Modulith の starter が入れる Moments は、既定で有効なとき `@EnableScheduling` を足し、この切り替えを効かなくする。
  Moments は使っていないため、`spring.modulith.moments.enabled` を偽にする。
- 実行の本体は、PostgreSQL のセッションの advisory lock（`pg_try_advisory_lock`、キー 108）を取れたインスタンスだけが行う。
  ロックは自動コミットの接続で持ち、実行の終わりに放す。
  取れなければ、その回は何もしない。
- 接続は `DSLContext.connection` で 1 本固定し、`Configuration.derive(Connection)` で Spring Boot の jOOQ の構成を保つ。
  このため `EventPublicationResubmitter` を H1 の規則（`tableWritesGoThroughTableWriter`）の例外に加える。
  このクラスは業務テーブルを書かず、`modulith.event_publication` を読むだけである。
- 再投入は `FailedEventPublications.resubmit(ResubmissionOptions)` で行い、独自の outbox の表を作らない。
  条件の関数で、再投入の回数（`completion_attempts - 1`）が上限未満で、最後の試行（`last_resubmission_date`）から待ち時間を過ぎた出版だけを選ぶ。
  待ち時間は条件の関数だけで強制し、Spring Modulith の最小の経過時間には頼らない。
  `LIMIT` は掛けず、1 回の件数の上限は条件の関数で数える。
- 試行の回数は Spring Modulith の `completion_attempts` で持ち、表を足さない。
- 上限に達した出版は自動の対象から外し、出版ごとの ERROR のログで知らせる。
  件数の Gauge（`state=exhausted`）は表示と確認に使い、警報にしない。
  最古の経過時間の Gauge（`state=exhausted`）は、`async-observability.md` の DLQ の滞留時間の監視として、1 日を超えたら通知する。
  ERROR はインスタンスごとに 1 回しか出ないため、見落とした出版をこの警報で拾う。
  その後は既存の手動の入口（`resubmit-once` の profile）と Runbook に引き継ぐ。
- `FAILED` の出版の件数と最古の経過時間の Gauge（`state=retrying`）は、`async-observability.md` の基盤のメトリクスの `FAILED` の部分として出す。
- ジョブの実行の回数（`event.publication.resubmission.runs`）と再投入の件数（`event.publication.resubmissions`）の Counter は、SLO がないためアプリケーションのメトリクスを出さないという `async-observability.md` の原則の例外とする。
  運用者がジョブの実行の結果と再投入の件数を確かめる要求（#108 の user story 4）があるためである。
- Staleness Monitor を待ち時間と同じ値で有効にし、落ちたまま残った `PUBLISHED`、`PROCESSING`、`RESUBMITTED` を `FAILED` に戻す。
  ジョブが `last_resubmission_date` から待ち時間を測るため、Staleness Monitor が再投入の直後に `FAILED` に戻しても、再投入の後は重ねて動かさない。
- 待ち時間はリスナーの処理時間より十分に長くする。
- 有効と無効、待ち時間、間隔は環境変数で注入し、上限の回数と 1 回の件数は `application.yaml` の固定値にする（ADR-008）。
- 起動時の自動再配信は、ADR-001 のとおり使わない。

shared の範囲は、ADR-048 の jOOQ の共通処理に加えて、Spring Modulith のイベント出版のレジストリの再投入まで広げる。
レジストリは全モジュールが共有する基盤で業務の概念を持たず、jOOQ を使う処理は `infrastructure.persistence` に置く規則があるためである。

## Consequences

### Positive

- 外部 API の一時的な障害で失敗した出版が、人の操作なしで回復する。
- 同時に再投入するインスタンスが 1 つになり、重複の処理と DB の負荷が増えない。
- 新しい依存も表も足さず、Spring Modulith の版を上げても同じ API と列を使い続けられる。
- インスタンスが落ちると接続が切れてロックが消えるため、ロックの期限の設定が要らない。
- 起動時の再配信と違い、デプロイのたびに全インスタンスが同時に再配信しない。

### Negative

- ジョブは実行中に DB の接続を 1 本持ち続け、接続の予算から 1 本を使う。
- セッションの advisory lock は、アプリから PostgreSQL への接続が直結であることを前提にする。RDS Proxy や PgBouncer のトランザクションモードのような接続を多重化するプロキシを入れるときは、ロックの方式を見直す。
- 実行の途中でロックの接続が切れると、PostgreSQL がロックを放すため、その回の残りの再投入は排他されない。その間の重複はリスナーの冪等性に頼る。
- 毎回 `FAILED` の行をすべて読む（待ち時間でも絞られない）ため、上限に達した出版を片付けないまま大量に溜めると、毎回の読み込みが重くなる。
- 接続を直接扱う H1 の例外のクラスが 1 つ増え、そのクラスの書き込みは ArchUnit で止められなくなる。
- イベントの型の名前を変えた出版は Spring Modulith が読み出しで落とすため、上限に達せず ERROR も出ない。WARN と最古の経過時間の警報で見つける。
- advisory lock の再入の回数は数えないため、解放に失敗した接続を同じインスタンスが再び使うと、回数が残る。
- 上限に達した通知の重複を避ける記録はインスタンスのメモリにあり、同じ出版の ERROR がインスタンスの数と再起動の回数だけ重なりうる。
- ジョブは失敗の原因を見分けないため、内部の不変条件の違反のデッドレター（ADR-072）も上限まで再投入し、そのたびにリスナーの ERROR が出る。
- advisory lock の解放に失敗し接続が壊れていないと、その接続が pool から退くまで他のインスタンスが実行を省く。
- 手動の入口はロックを取らないため、同時に動くと同じ出版を重ねて再投入しうる。リスナーが冪等であることに頼る。
- 最初の配信が `concurrency-limit` の空きを待つ間に待ち時間を過ぎた出版は、Staleness Monitor が `FAILED` に戻し、ジョブが重ねて再投入しうる。その場合はリスナーの冪等性に頼る。その間、`FAILED` の出版の Gauge は処理中の出版も数える。

### Neutral

- 再投入した 1 件ごとの成否はジョブに返らず、`FAILED` の出版の Gauge の変化で見る。
- 本番と STG の環境変数の注入は、ECS のタスク定義を作るときに足す。
- archive の表の保存期間と消し方は別の Issue で決める。
- ADR-019 の「retry は client adapter の一層だけ」は呼び出しの即時の再試行についての決定であり、分単位の間隔と回数の上限を持つ出版の再投入はそれと別の層である。
  1 件の出版あたりの外部の呼び出しは、client adapter の試行の回数と再投入の回数の積が上限になる。

## Alternatives Considered

### 選択肢1: 起動時の自動再配信（`republish-outstanding-events-on-restart`）

- **Description**：起動のたびに未完了の出版をすべて再配信する。
- **Pros**：設定一つで済む。
- **Cons**：ADR-001 のとおり、複数のインスタンスが同時に同じ出版を再配信し、処理中の出版も対象にする。起動しない限り回復しない。

### 選択肢2: ShedLock

- **Description**：ShedLock の `@SchedulerLock` と JDBC の lock provider で、ジョブを 1 つのインスタンスに絞る。
- **Pros**：定期のジョブの排他として広く使われ、ロックの期限で異常な長時間の実行も打ち切れる。
- **Cons**：依存と専用の表と Liquibase の changeset が増える。ロックの期限をジョブの長さより長く決める必要があり、短いと二つ目のインスタンスが動く。advisory lock は接続が切れれば消えるため、期限が要らない。

### 選択肢3: 専用の表の行ロック、またはトランザクションの advisory lock

- **Description**：ロックの行を `SELECT ... FOR UPDATE SKIP LOCKED` で取るか、`pg_try_advisory_xact_lock` を使い、トランザクションの終わりで放す。
- **Pros**：ロックの解放をトランザクションに任せられる。
- **Cons**：トランザクションを開いたまま再投入すると、`markResubmitted` の行ロックをトランザクションの終わりまで持ち、`REQUIRES_NEW` のリスナーの `markProcessing` が待つ。トランザクションを停止して別の接続で再投入すると、ロックの接続が `idle_in_transaction_session_timeout` で切られる。

### 選択肢4: 排他なしで `markResubmitted` の条件に任せる

- **Description**：各インスタンスで同じジョブを動かし、Spring Modulith の更新の条件で重複を防ぐ。
- **Pros**：コードが最も少ない。
- **Cons**：先の再投入が `PROCESSING` や `COMPLETED` に進めた行は二つ目の更新の条件を満たし、同じ出版を重ねて再投入する。

### 選択肢5: 試行の回数を独自の表に持つ

- **Description**：出版の ID ごとに再投入の回数と通知の済みを記録する表を作る。
- **Pros**：通知の重複を DB で防げる。
- **Cons**：Spring Modulith 2.1.1 は回数を `completion_attempts` で持つため二重の管理になる。通知の重複のためだけに表とマイグレーションを足すほどの効果がない。

### 選択肢6: `ResubmissionOptions` の件数の上限（`LIMIT`）を使う

- **Description**：`withBatchSize` と `withMaxInFlight` で 1 回の件数を SQL で絞る。
- **Pros**：毎回の読み込みが件数の上限で抑えられる。
- **Cons**：`LIMIT` が条件の関数より先に掛かるため、上限に達した古い出版が枠を占め、新しい失敗に届かなくなる。

## References

- [ADR-001: Spring Modulith によるモジュラーモノリス](ADR-001-adopt-spring-modulith-modular-monolith.md)
- [ADR-006: 絶対時刻を UTC / Instant / timestamptz に統一する](ADR-006-utc-instant-absolute-time-policy.md)
- [ADR-008: application.yaml を単一にし、設定を外部から注入する](ADR-008-single-application-yaml-external-config.md)
- [ADR-015: 可観測性データを構造化し保護する](ADR-015-structure-and-protect-observability-data.md)
- [ADR-019: 外部連携の耐障害性と容量制御を標準化する](ADR-019-define-resilience-and-capacity-guardrails.md)
- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-054: 楽観的ロックの競合を lock_no の条件と更新件数で判定し、業務テーブルの UPDATE と DELETE を TableWriter に集める](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)
- [ADR-070: SQL の span を jOOQ の ExecuteListener で作り、SQL の文と値を入れない](ADR-070-record-sql-spans-with-jooq-execute-listener.md)
- [ADR-072: 外部システムを本番のコードで偽らず、WireMock のコンテナで偽る](ADR-072-fake-external-systems-with-wiremock.md)
- [非同期処理の失敗時の再試行と回復](../integration/async-failure-recovery.md)
- [非同期処理のトレースと監視](../integration/async-observability.md)
- [PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)
- [イベント出版の再投入のログイベント](../observability/runbook-event-publication-resubmission.md)
- [issue #108](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/108)
- [JdbcEventPublicationRepositoryV2.java（2.1.1）](https://github.com/spring-projects/spring-modulith/blob/2.1.1/spring-modulith-events/spring-modulith-events-jdbc/src/main/java/org/springframework/modulith/events/jdbc/JdbcEventPublicationRepositoryV2.java)
- [DefaultEventPublicationRegistry.java（2.1.1）](https://github.com/spring-projects/spring-modulith/blob/2.1.1/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/core/DefaultEventPublicationRegistry.java)
- [PostgreSQL: Advisory Lock Functions](https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS)
