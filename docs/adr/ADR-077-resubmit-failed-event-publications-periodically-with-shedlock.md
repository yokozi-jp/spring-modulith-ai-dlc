---
type: ADR
title: 'ADR-077: 失敗したイベント出版を、ShedLock で 1 つのインスタンスに絞った定期のジョブで、リスナーごとの方針で再投入する'
description: 失敗したイベント出版の自動の回復を、起動時の再配信ではなく、DB の時刻で期限を決める ShedLock のロックで排他し、リスナーごとの名前付きの方針と指数的な待ち時間で絞った定期のジョブで行い、手での再投入を 1 回だけ起動するジョブにする決定。
tags: [adr, integration, async, spring-modulith, resilience, postgresql]
---

# ADR-077: 失敗したイベント出版を、ShedLock で 1 つのインスタンスに絞った定期のジョブで、リスナーごとの方針で再投入する

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

このリポジトリは多くのプロジェクトが写して使うテンプレートである。
アプリから PostgreSQL への接続が直結か、RDS Proxy や PgBouncer のトランザクションモードのような接続を多重化するプロキシを通るか、インスタンスがいくつ動くかは、利用先ごとに変わる。
接続を多重化するプロキシの後ろでは、セッションに残る状態（セッションの advisory lock、`SET`、`LISTEN` など）が、同じ接続の次のトランザクションに保たれない（[PgBouncer の機能の表](https://www.pgbouncer.org/features.html)）。
排他の仕組みは、この違いに左右されないものにする必要がある（[設計の作り込みの程度と段階的な再構築](../principles/right-sizing.md)の「テンプレートの利用先で変わる前提」）。

Spring Modulith 2.1.1 の JDBC の V2 のスキーマは、状態、試行の回数（`completion_attempts`）、最後の再投入の時刻（`last_resubmission_date`）を持ち、main の Liquibase の changeset はこれらの列を作ってある。
`FailedEventPublications.resubmit(ResubmissionOptions)` は、`FAILED` の出版を最小の経過時間と条件の関数で絞って再投入できる。
ただし、件数の上限は条件の関数より先に SQL の `LIMIT` で掛かり、`resubmit` は再投入した件数を返さない。
最小の経過時間は、2.1.1 の SQL の演算子の優先順位のため `FAILED` の行に効かない。

リスナーごとに、外部システムの回復にかかる時間も、何度まで自動で試してよいかも違う。
一律の待ち時間と回数では、長い障害の間に短い間隔で再投入を繰り返すか、短い障害の回復を待たせすぎる。

Staleness Monitor は落ちたまま残った出版を `FAILED` に戻せるが、`publication_date` から測るため、古い出版を再投入した直後にも `FAILED` に戻すことがある。

## Decision

失敗したイベント出版を、定期のジョブで自動で再投入する。

- ジョブは `shared.infrastructure.persistence` の `EventPublicationResubmitter` に置き、`@Scheduled` の固定の遅延で動かす。
  `event-publication.resubmission.enabled` が偽なら、定期の実行も Staleness Monitor も登録しない。
  Spring Modulith の starter が入れる Moments は、既定で有効なとき `@EnableScheduling` を足し、この切り替えを効かなくする。
  Moments は使っていないため、`spring.modulith.moments.enabled` を偽にする。
- 実行の本体は、ShedLock のロック（`event-publication-resubmission`）を取れたインスタンスだけが行う。
  ロックは ShedLock の jOOQ の provider で `modulith.shedlock` の行に持ち、期限を DB の時刻で書き込んで比べる。
  ロックの操作は 1 文ずつの新しいトランザクション（`REQUIRES_NEW`）で終わり、接続を固定しない。
  ShedLock は `@SchedulerLock` ではなく `LockingTaskExecutor` で使い、定期のジョブと手での 1 回の再投入が同じロックを使う。
- ロックを持つ上限（`lock-at-most-for`）は `application.yaml` の固定値（10 分）にし、1 回の実行の最長より十分に長くする。
  1 回の実行は `max-per-run` 件で打ち切る。
  ロックを持つ下限は間隔の半分にし、N 台が動いてもクラスタ全体の実行が間隔あたりおよそ 1 回から 2 回に収まるようにする。
  下限を間隔と同じにしないのは、1 台のときに、DB の時刻とインスタンスの時刻の測り方の差で自分の次の回を省きうるためである。
- 再投入の方針はリスナーごとに名前を付けて持つ（`event-publication.resubmission.policies.<名前>` の `max-resubmissions`、`multiplier`、`max-wait`）。
  リスナーの `@ApplicationModuleListener` の `on` に `@ResubmissionPolicy("<名前>")` を付けて選び、付けなければ `default` にする。
  `default` は必須で、設定にない名前を付けると起動に失敗する。
  注釈は、機能モジュールのどの層からも使える `shared.events` に置く。
- 出版と方針は、出版のリスナーの ID（`listener_id`）で対応づける。
  起動のときに bean のメソッドを走査し、Spring Framework がリスナーの ID を作る規則（注釈の `id`、空ならメソッドの完全修飾名と引数の型）で同じ ID を作る。
- n 回目の再投入の前に、最後の試行（`last_resubmission_date`、なければ `publication_date`）から `min(wait-age × multiplier^(n-1), max-wait)` を待つ。
  n は `completion_attempts`（再投入した回数 + 1）である。
  ゆらぎ（jitter）は入れない。
  `wait-age` は全体で 1 つの値で、Staleness Monitor の閾値と最初の待ち時間を兼ねる。
- 再投入は `FailedEventPublications.resubmit(ResubmissionOptions)` で行い、独自の outbox の表を作らない。
  条件の関数で、方針の上限未満で、待ち時間を過ぎた出版だけを選ぶ。
  待ち時間は条件の関数だけで強制し、Spring Modulith の最小の経過時間には頼らない。
  `LIMIT` は掛けず、1 回の件数の上限は条件の関数で数える。
- 2.1.1 の二つの癖（最小の経過時間が `FAILED` に効かない、`LIMIT` が条件の関数より先に掛かる）は、学習テスト（`ResubmissionOptionsLearningTest`）で固定する。
  Spring Modulith の版を上げて癖が直ると、このテストが失敗して、読み方を見直す合図になる。
- 試行の回数は Spring Modulith の `completion_attempts` で持ち、表を足さない。
- 上限に達した出版は自動の対象から外し、出版ごとの ERROR のログで知らせる。
  上限はリスナーの方針で決まる。
  ERROR の重複を避ける記録はインスタンスのメモリに持ち、監視の通知は `event_publication.id` でまとめる。
  件数の Gauge（`state=exhausted`）は表示と確認に使い、警報にしない。
  最古の経過時間の Gauge（`state=exhausted`）は、`async-observability.md` の DLQ の滞留時間の監視として、1 日を超えたら通知する。
  その後は手での 1 回の再投入と Runbook に引き継ぐ。
- `FAILED` の出版の件数と最古の経過時間の Gauge（`state=retrying`、`state=exhausted`）は、ロックの外ですべてのインスタンスが集計する。
  どのインスタンスも同じ値を出すため、ダッシュボードと警報はインスタンスの間の最大値（`max`）で集め、合計しない。
- ジョブの実行の回数（`event.publication.resubmission.runs`）と再投入の件数（`event.publication.resubmissions`）の Counter は、SLO がないためアプリケーションのメトリクスを出さないという `async-observability.md` の原則の例外とする。
  運用者がジョブの実行の結果と再投入の件数を確かめる要求（#108 の user story 4）があるためである。
- 手での再投入は、同じイメージを 1 回だけ起動するジョブ（`EventPublicationResubmitOnce`）にする。
  `event-publication.resubmit-once.target`（`all`、`policy:<名前>`、`event-type:<クラス>`）を渡したときだけ動き、Web のサーバーと定期の実行と Staleness Monitor を動かさない。
  定期のジョブと同じロックを、`lock-at-most-for` を上限に待って取り、待ち時間と上限を見ずに対象の `FAILED` の出版を `max-per-run` 件まで再投入する。
  再投入した出版が `PUBLISHED`、`PROCESSING`、`RESUBMITTED` でなくなるまで待ち、件数を記録して、すべて完了なら終了コード 0、それ以外は 1 で JVM を終える。
  リスナーは別のスレッドで動くため、待たずに終えると処理の途中で切れる。
  `resubmit-once` の profile と `ResubmitFailedPaymentsRunner` は消す。
- Staleness Monitor を `wait-age` と同じ値で有効にし、落ちたまま残った `PUBLISHED`、`PROCESSING`、`RESUBMITTED` を `FAILED` に戻す。
  ジョブが `last_resubmission_date` から `wait-age` 以上を待つため、Staleness Monitor が再投入の直後に `FAILED` に戻しても、再投入の後は重ねて動かさない。
- `wait-age` はリスナーの処理時間より十分に長くする。
- 有効と無効、`wait-age`、間隔は環境変数で注入し、方針、1 回の件数、ロックを持つ上限、手での再投入の完了を待つ上限は `application.yaml` の固定値にする（ADR-008）。
- 本番のコードがセッションに状態を残す SQL を使わないことを、`task lint-session-sql` で検査する。
- 起動時の自動再配信は、ADR-001 のとおり使わない。

shared の範囲は、ADR-048 の jOOQ の共通処理に加えて、Spring Modulith のイベント出版のレジストリの再投入と、その方針をリスナーが選ぶ注釈まで広げる。
レジストリは全モジュールが共有する基盤で業務の概念を持たず、jOOQ を使う処理は `infrastructure.persistence` に置く規則があるためである。

## Consequences

### Positive

- 外部 API の一時的な障害で失敗した出版が、人の操作なしで回復する。
- 同時に再投入するインスタンスが 1 つになり、重複の処理と DB の負荷が増えない。
- 接続が直結でも、接続を多重化するプロキシの後ろでも、インスタンスがいくつでも同じ設定で動く。
- ロックの期限を DB の時刻で決めるため、インスタンスの時計のずれで排他が崩れない。
- ジョブは接続を固定せず、`TableWriter` の規則（H1）の例外も要らない。
- リスナーごとに回数と待ち時間を変えられ、長い障害の間の再投入の回数を指数的な待ち時間で抑えられる。
- 手での再投入が定期のジョブと同じロックを取るため、重ねて再投入せず、定期のジョブを止める手順が要らない。
- 手での再投入の結果を終了コードで返すため、ECS の RunTask や Kubernetes の Job の成否で確かめられる。
- 起動時の再配信と違い、デプロイのたびに全インスタンスが同時に再配信しない。

### Negative

- ShedLock の依存と、`modulith.shedlock` の表と Liquibase の changeset が増える。
- ロックを持つ上限は 1 回の実行の最長より長くする必要があり、実行がそれを超えると二つ目のインスタンスが動く。その間の重複はリスナーの冪等性に頼る。
- インスタンスが落ちてロックを放さないと、ロックを持つ上限（10 分）まで再投入が止まる。
- ShedLock はロックの行を作ったことをメモリに覚えるため、`modulith.shedlock` の行を手で消すと、再起動まで同じインスタンスがロックを取れない。
- 毎回 `FAILED` の行をすべて読む（待ち時間でも絞られない）ため、上限に達した出版を片付けないまま大量に溜めると、毎回の読み込みが重くなる。
- 方針の対応づけは Spring Framework のリスナーの ID の規則を写すため、その規則が変わると方針が `default` に戻る。結合テストが実際の出版の ID と比べて検出する。
- イベントの型の名前を変えた出版は Spring Modulith が読み出しで落とすため、上限に達せず ERROR も出ない。WARN と最古の経過時間の警報で見つける。
- 上限に達した通知の重複を避ける記録はインスタンスのメモリにあり、同じ出版の ERROR がロックを取ったインスタンスの数と再起動の回数だけ重なりうる。
- ジョブは失敗の原因を見分けないため、内部の不変条件の違反のデッドレター（ADR-072）も上限まで再投入し、そのたびにリスナーの ERROR が出る。
- 最初の配信が `concurrency-limit` の空きを待つ間に `wait-age` を過ぎた出版は、Staleness Monitor が `FAILED` に戻し、ジョブが重ねて再投入しうる。その場合はリスナーの冪等性に頼る。その間、`FAILED` の出版の Gauge は処理中の出版も数える。
- 指数的な待ち時間のため、上限に達するまでの時間が延び（本番の `default` でおよそ 2 時間 20 分）、`failed.oldest.age{state=retrying}` の警報の閾値もそれに合わせて長くなる。

### Neutral

- 再投入した 1 件ごとの成否は定期のジョブに返らず、`FAILED` の出版の Gauge の変化で見る。手での 1 回の再投入は件数を記録する。
- 本番と STG の環境変数の注入は、ECS のタスク定義を作るときに足す。
- archive の表の保存期間と消し方は別の Issue で決める。
- `spring.datasource.hikari.connection-init-sql` の `SET` と HikariCP の `schema`（`search_path`）もセッションの設定であり、接続を多重化するプロキシの後ろでは保たれない。この ADR では扱わず、`lint-session-sql` も検出しない。
- ADR-019 の「retry は client adapter の一層だけ」は呼び出しの即時の再試行についての決定であり、分単位の間隔と回数の上限を持つ出版の再投入はそれと別の層である。
  1 件の出版あたりの外部の呼び出しは、client adapter の試行の回数と再投入の回数の積が上限になる。

## Alternatives Considered

### 選択肢1: 起動時の自動再配信（`republish-outstanding-events-on-restart`）

- **Description**：起動のたびに未完了の出版をすべて再配信する。
- **Pros**：設定一つで済む。
- **Cons**：ADR-001 のとおり、複数のインスタンスが同時に同じ出版を再配信し、処理中の出版も対象にする。起動しない限り回復しない。

### 選択肢2: PostgreSQL のセッションの advisory lock

- **Description**：自動コミットの接続を 1 本固定し、`pg_try_advisory_lock` を取れたインスタンスだけが実行する。
- **Pros**：依存も表も増えない。インスタンスが落ちると接続が切れてロックが消えるため、ロックの期限が要らない。
- **Cons**：ロックはセッションの状態であり、RDS Proxy や PgBouncer のトランザクションモードの後ろでは、別のクライアントの接続に残るか、次の文で失われる。接続の経路は利用先ごとに変わり、テンプレートはそれを前提にできない。接続の固定のため H1 の例外が要り、実行中は接続の予算から 1 本を使う。

### 選択肢3: 専用の表の行ロック、またはトランザクションの advisory lock

- **Description**：ロックの行を `SELECT ... FOR UPDATE SKIP LOCKED` で取るか、`pg_try_advisory_xact_lock` を使い、トランザクションの終わりで放す。
- **Pros**：ロックの解放をトランザクションに任せられ、接続を多重化するプロキシの後ろでも動く。
- **Cons**：トランザクションを開いたまま再投入すると、`markResubmitted` の行ロックをトランザクションの終わりまで持ち、`REQUIRES_NEW` のリスナーの `markProcessing` が待つ。トランザクションを停止して別の接続で再投入すると、ロックの接続が `idle_in_transaction_session_timeout` で切られる。

### 選択肢4: 排他なしで `markResubmitted` の条件に任せる

- **Description**：各インスタンスで同じジョブを動かし、Spring Modulith の更新の条件で重複を防ぐ。
- **Pros**：コードが最も少ない。
- **Cons**：先の再投入が `PROCESSING` や `COMPLETED` に進めた行は二つ目の更新の条件を満たし、同じ出版を重ねて再投入する。

### 選択肢5: 試行の回数を独自の表に持つ

- **Description**：出版の ID ごとに再投入の回数と通知の済みを記録する表を作る。
- **Pros**：通知の重複を DB で防げる。
- **Cons**：Spring Modulith 2.1.1 は回数を `completion_attempts` で持つため二重の管理になる。通知の重複は監視で `event_publication.id` ごとにまとめれば足り、表とマイグレーションを足すほどの効果がない。

### 選択肢6: `ResubmissionOptions` の件数の上限（`LIMIT`）を使う

- **Description**：`withBatchSize` と `withMaxInFlight` で 1 回の件数を SQL で絞る。
- **Pros**：毎回の読み込みが件数の上限で抑えられる。
- **Cons**：`LIMIT` が条件の関数より先に掛かるため、上限に達した古い出版が枠を占め、新しい失敗に届かなくなる。

### 選択肢7: 全体で一律の待ち時間と回数の上限

- **Description**：`wait-age` と `max-resubmissions` を全リスナーで共有し、待ち時間を延ばさない。
- **Pros**：設定と注釈が要らない。
- **Cons**：リスナーごとの外部システムの回復時間の違いを表せず、長い障害の間も同じ短い間隔で再投入を繰り返す。

### 選択肢8: 定期のジョブを止めて手での再投入を起動する（main の `resubmit-once` の profile）

- **Description**：手での再投入はロックを取らず、重ねたくなければ先に定期のジョブを止める。
- **Pros**：手での入口のコードが少ない。
- **Cons**：手順を忘れると重ねて再投入する。起動したアプリが Web のサーバーと定期の実行も動かし、リスナーの完了を待たずに終わるかどうかが起動の仕方で変わる。

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
- [設計の作り込みの程度と段階的な再構築](../principles/right-sizing.md)
- [非同期処理の失敗時の再試行と回復](../integration/async-failure-recovery.md)
- [非同期処理のトレースと監視](../integration/async-observability.md)
- [PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)
- [イベント出版の再投入のログイベント](../observability/runbook-event-publication-resubmission.md)
- [issue #108](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/108)
- [JdbcEventPublicationRepositoryV2.java（2.1.1）](https://github.com/spring-projects/spring-modulith/blob/2.1.1/spring-modulith-events/spring-modulith-events-jdbc/src/main/java/org/springframework/modulith/events/jdbc/JdbcEventPublicationRepositoryV2.java)
- [DefaultEventPublicationRegistry.java（2.1.1）](https://github.com/spring-projects/spring-modulith/blob/2.1.1/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/core/DefaultEventPublicationRegistry.java)
- [ApplicationListenerMethodAdapter.java（Spring Framework 7.0.9）](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/context/event/ApplicationListenerMethodAdapter.java)
- [ShedLock 7.10.1](https://github.com/lukas-krecan/ShedLock/tree/shedlock-parent-7.10.1)
- [PgBouncer: Features（SQL feature map for pooling modes）](https://www.pgbouncer.org/features.html)
