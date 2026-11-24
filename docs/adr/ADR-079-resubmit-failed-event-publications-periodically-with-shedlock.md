---
type: ADR
title: 'ADR-079: 失敗したイベント出版を、ShedLock で 1 つのインスタンスに絞った定期のジョブで、リスナーごとの方針で再投入する'
description: 失敗したイベント出版の自動の回復を、起動時の再配信ではなく、DB の時刻で期限を決める ShedLock のロックで排他し、リスナーごとの名前付きの方針と指数的な待ち時間で絞った定期のジョブで行い、回数の上限を超えた出版を Spring Modulith 2.2 の abandon-after-attempts で ABANDONED にし、手での再投入を 1 回だけ起動するジョブにする決定。
tags: [adr, integration, async, spring-modulith, resilience, postgresql]
---

# ADR-079: 失敗したイベント出版を、ShedLock で 1 つのインスタンスに絞った定期のジョブで、リスナーごとの方針で再投入する

## Status

Proposed

## Date

2026-10-10

## Context

リスナーが失敗したイベント出版は、Spring Modulith のレジストリに `FAILED` で残る。
main では、運用者が `resubmit-once` の profile でアプリを起動して再投入するまで、二度と処理されない。
決済代行の数分の障害でも、確定済みで未払いの注文が人の対応まで残る（ADR-050、ADR-072）。
この ADR は、ADR-072 が定めた失敗した出版の扱いを変える。
ADR-072 のデッドレターも自動の再投入の対象になり、運用者の入口は手での 1 回の再投入になる。
ADR-072 の他の決定（外部システムを WireMock のコンテナで偽る）は変えず、ADR-072 全体を置き換えない。

起動時の自動再配信（`republish-outstanding-events-on-restart`）は、複数のインスタンスが同時に同じ出版を再配信するため使わない（ADR-001）。
定期のジョブにしても、`@Scheduled` はインスタンスごとに動く。
Spring Modulith（2.2.0）の `markResubmitted` の条件は `COMPLETION_DATE IS NULL AND (STATUS IS NULL OR STATUS != 'RESUBMITTED')` であり、先の再投入でリスナーが `PROCESSING` に進めた行は二つ目の更新でも条件を満たすため、排他にならない。

このリポジトリは多くのプロジェクトが写して使うテンプレートである。
アプリから PostgreSQL への接続が直結か、RDS Proxy や PgBouncer のトランザクションモードのような接続を多重化するプロキシを通るか、インスタンスがいくつ動くかは、利用先ごとに変わる。
接続を多重化するプロキシの後ろでは、セッションに残る状態（セッションの advisory lock、`SET`、`LISTEN` など）が、同じ接続の次のトランザクションに保たれない（[PgBouncer の機能の表](https://www.pgbouncer.org/features.html)）。
排他の仕組みは、この違いに左右されないものにする必要がある（[設計の作り込みの程度と段階的な再構築](../principles/right-sizing.md)の「テンプレートの利用先で変わる前提」）。

Spring Modulith の JDBC の V2 のスキーマは、状態、試行の回数（`completion_attempts`）、最後の再投入の時刻（`last_resubmission_date`）を持ち、main の Liquibase の changeset はこれらの列を作ってある。
`FailedEventPublications.resubmit(ResubmissionOptions)` は、`FAILED` の出版を最小の経過時間と条件の関数で絞って再投入できる。
ただし、件数の上限は条件の関数より先に SQL の `LIMIT` で掛かり、`resubmit` は再投入した件数を返さない。
最小の経過時間は `publication_date` から測るため、最後の試行からの待ち時間を表せない。
最小の経過時間が既定の 0 でも、`publication_date` が今より前の `FAILED` の出版だけを読む。

リスナーごとに、外部システムの回復にかかる時間が違う。
何度まで自動で試してよいかもリスナーごとに違いうるが、main の 2 つの方針（`default` と `payment`）はどちらも 5 回である。
一律の待ち時間では、長い障害の間に短い間隔で再投入を繰り返すか、短い障害の回復を待たせすぎる。

Spring Modulith の `EventPublicationRegistry.markStalePublicationsFailed`（組み込みの Staleness Monitor が定期に呼ぶ）は、落ちたまま残った出版を `FAILED` に戻せる。
2.2.0 は、`PUBLISHED`、`PROCESSING`、`RESUBMITTED` のどれも、最後の出版か再投入の時刻（`last_resubmission_date`、なければ `publication_date`）から測る。
その状態に入ってからの時間では測らないため、`PROCESSING` の閾値が 1 回の配信（executor の空きを待つ時間とリスナーの処理）の最長より短いと、処理中の出版を `FAILED` に戻す。

再投入の結果を知らせる公開の hook もない。

Spring Modulith 2.2 は、終端の状態 `ABANDONED` と `AbandonPolicy`（[GH-1764](https://github.com/spring-projects/spring-modulith/issues/1764)）を持つ。
リスナーが失敗するたびに `AbandonPolicy` の判断を聞き、`ABANDON` なら出版を `FAILED` でなく `ABANDONED` にする。
利用者の `AbandonPolicy` が判断しなければ、`spring.modulith.events.resubmission.abandon-after-attempts` と、渡された出版の `getCompletionAttempts()` を比べ、それ以上なら諦める。
`ABANDONED` の出版は、再投入にも `markStalePublicationsFailed` にも読まれない。
`completion-mode: archive` では、`COMPLETED` と同じく archive の表へ移る（2.2.0 の `JdbcEventPublicationRepositoryV2.markAbandoned`）。
Spring Boot の版は [ADR-122](ADR-122-raise-spring-boot-4-2-after-spring-modulith-2-2.md) で決める。

2.2.0 の `AbandonPolicy.shouldAbandon` は `EventPublication` だけを受け取る。
`EventPublication` には失敗の例外を返すメソッドがなく、`CompletionRegisteringAdvisor` も例外を `markFailed` に渡さない（2.2.0 の `CompletionRegisteringAdvisor.java:153-181`）。
そのため、直らない例外（内部の不変条件の違反、`NotFoundException` など）を上限を待たずに諦めることは、2.2 の API だけでは書けない。

`AbandonPolicy` に渡る出版の値は、`ResubmissionOptionsLearningTest` の `abandonPolicySeesPublicationReadBeforeResubmission` で記録した。

| 失敗した試行     | `getStatus()` | `getCompletionAttempts()` | `getLastResubmissionDate()` | `getPublicationDate()` | `getLastPublicationDate()` |
| ---------------- | ------------- | ------------------------- | --------------------------- | ---------------------- | -------------------------- |
| 最初の配信       | `PUBLISHED`   | 1                         | T0                          | T0                     | T0                         |
| 1 回目の再投入   | `FAILED`      | 1                         | T0                          | T0                     | T0                         |
| 2 回目の再投入   | `FAILED`      | 2                         | T1（1 回目の再投入の時刻）  | T0                     | T1                         |

最初の配信では、出版を作ったときのメモリの値が渡る。
再投入では、再投入の前に読んだ `FAILED` の行の値が渡り、`completion_attempts` は再投入で 1 増えた行の値より 1 小さい。
最初の配信と 1 回目の再投入は、状態だけが違い、回数も時刻も同じである。
そのため、`abandon-after-attempts` を N（2 以上）にすると、N 回目の再投入の失敗で諦め、1 にすると最初の配信の失敗で諦める。
Proposed の間に回数の上限を自前の `AbandonPolicy` から `abandon-after-attempts` へ書き直した理由は、この表と選択肢10 にある。

Proposed の間に、上限を超えて `FAILED` に残った出版の知らせ方を、ロックを取った回ごとの ERROR から、`state=over-limit` の Gauge の警報と、件数が 0 から増えたときだけの ERROR へ書き直した。
回ごとの ERROR は、運用者が扱うまで `interval`（本番 1 分）ごとに同じ通知を出し続け、警報が埋もれるためである。

Proposed の間に、古い出版を `FAILED` に戻す処理を、組み込みの Staleness Monitor から、再投入のジョブがロックの中で呼ぶ形へ書き直した（[#556](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/556)）。
組み込みの Staleness Monitor は全インスタンスがロックの外で動かし、古い出版を読んだ後に状態を確かめずに `markFailed` を呼ぶ。
そのため、その間に別のインスタンスが再投入した出版を処理中のまま `FAILED` に戻し、同じ出版のリスナーを同時に 2 本動かしうる（`ResubmissionOptionsLearningTest` の `stalenessMarksPublicationResubmittedAfterRead`、形式検証の当時の既知の発見）。

## Decision

失敗したイベント出版を、定期のジョブで自動で再投入する。

- ジョブは `shared.eventresubmission` の `EventPublicationResubmitter` に置き、`@Scheduled` の固定の遅延で動かす。
  `event-publication.resubmission.enabled` が `true` 以外なら、定期の実行を登録せず、古い出版を `FAILED` に戻す処理も再投入も動かない。
  この条件は `true`（大文字小文字を区別しない）だけを真と読むため、環境変数には `true` か `false` だけを使う。
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
- 定期の 1 回の実行は、開始から `lock-at-most-for / 2` を過ぎたら、以降の候補を選ばずに次の回へ回す。
  開始から測るため、ロックの中で先に動く古い出版を `FAILED` に戻す処理の時間も予算に入る。
  予算は `lock-at-most-for` から導き、設定値を増やさない。
  古い出版から処理するため、残りは次の回で先に処理され、飢えない。
  ShedLock の `KeepAliveLockProvider` は採らない。
  解放まで延長し続けるため、実行が固まるとロックが無期限に残り、最悪でも `lock-at-most-for` で他のインスタンスが引き継げる今の設計を、無期限の停止に変えるからである。
  `lock-at-most-for` が 30 秒未満だと使えない制約もあり、専用のスケジューラと停止の処理も増える。
  手での 1 回の再投入には予算を適用しない。
  運用者が完了を待つ入口であり、`max-per-run` が件数を抑える。
- 待ち時間の方針はリスナーごとに名前を付けて持つ（`event-publication.resubmission.policies.<名前>` の `multiplier`、`max-wait`）。
  リスナーの `@ApplicationModuleListener` の `on` に `@ResubmissionPolicy("<名前>")` を付けて選び、付けなければ `default` にする。
  `default` は必須で、設定にない名前を付けると起動に失敗する。
  注釈は、機能モジュールのどの層からも使える `shared.events` に置く。
- 出版と方針は、出版のリスナーの ID（`listener_id`）で対応づける。
  起動のときに bean のメソッドを走査し、Spring Framework がリスナーの ID を作る規則（注釈の `id`、空ならメソッドの完全修飾名と引数の型）で同じ ID を作る。
- n 回目の再投入の前に、最後の試行（`last_resubmission_date`、なければ `publication_date`）から `min(wait-age × multiplier^(n-1), max-wait)` を待つ。
  n は `completion_attempts`（再投入した回数 + 1）である。
  ゆらぎ（jitter）は入れない。
  `wait-age` は全体で 1 つの値で、`PUBLISHED` の出版を古いとして `FAILED` に戻す閾値と最初の待ち時間を兼ねる。
- 再投入は `FailedEventPublications.resubmit(ResubmissionOptions)` で行い、独自の outbox の表を作らない。
  条件の関数（`ResubmissionPolicies.isDue`）で、回数の上限に達しておらず、待ち時間を過ぎた出版だけを選ぶ。
  待ち時間は条件の関数だけで強制し、Spring Modulith の最小の経過時間には頼らない。
  `LIMIT` は掛けず、1 回の件数の上限は条件の関数で数える。
- `LIMIT` が条件の関数より先に掛かること、既定の最小の経過時間でも `publication_date` が今と同じ出版を読まないこと、`markStalePublicationsFailed` が状態ごとの閾値より古い出版だけを `FAILED` にし、処理中の出版を `last_resubmission_date` から測ること、読んだ後に再投入された出版も `FAILED` にすること、`AbandonPolicy` に渡る出版の値（Context の表）は、学習テスト（`ResubmissionOptionsLearningTest`）で固定する。
  `EventPublicationRegistry` は Spring Modulith の `core` のパッケージにあり、版を上げたときに変わりうる。
  Spring Modulith の版を上げて振る舞いが変わると、このテストが失敗して、読み方を見直す合図になる。
- 試行の回数は Spring Modulith の `completion_attempts` で持ち、表を足さない。
- 回数の上限は、Spring Modulith の `spring.modulith.events.resubmission.abandon-after-attempts`（5）で全リスナーに共通に持ち、自前の `AbandonPolicy` を作らない。
  Context の表のとおり、5 回目の再投入の失敗で出版が `ABANDONED` になる。
  1 は最初の配信の失敗で諦めるため、2 未満の値と未設定（Spring Modulith は諦めない）は起動に失敗させる（`EventPublicationResubmissionConfig.AbandonProperties`）。
  `isDue` も同じ値を読み、`completion_attempts`（再投入した回数 + 1）がこの値を超えた出版を再投入しない。
  回数を見るのは、失敗を記録しない試行（最後の再投入の途中の停止の後に、古い出版として `FAILED` に戻した出版）で上限を超えて `FAILED` に戻った出版を、上限を超えて再投入しないためである。
  この出版には `AbandonPolicy` が呼ばれないため、`ABANDONED` にならない。
  回数の条件を外すと、停止の後に古い出版として `FAILED` に戻した出版を上限を超えて再投入することを、形式検証（[formal-verify-event-resubmission](../../.agents/skills/formal-verify-event-resubmission/SKILL.md)）の `ResubmissionsCapped` が捕まえる。
- 直らない例外を、上限を待たずに諦めることは取り込まない。
  2.2 の `AbandonPolicy` は失敗の例外を受け取らないためである（Context）。
  直らない例外も、回数の上限まで再投入する。
  例外の種類を自作で記録して `AbandonPolicy` から読む形は、自作を増やすため採らない。
  例外を `AbandonPolicy` に渡す拡張は、spring-projects/spring-modulith に求められる。
- `ABANDONED` の出版は、`completion-mode: archive` により archive の表に `status = 'ABANDONED'` で移り、`completion_date` は諦めた時刻になる。
  定期の再投入、手での 1 回の再投入、古い出版を `FAILED` に戻す処理のどれも、`ABANDONED` の出版を読まない。
  archive の保持期限は `ABANDONED` の行に掛けない（[ADR-120](ADR-120-keep-abandoned-event-publications-out-of-archive-retention.md)）。
- 上限に達した出版、上限を超えて `FAILED` に残った出版、自動の再投入を待つ出版を、ログとメトリクスで分けて知らせる。
  Gauge の `state` の意味、ログの event 名と属性、警報の置き方、集め方（`max` と `sum`）の正本は [Runbook](../observability/runbook-event-publication-resubmission.md) である。
  - 上限に達した出版は、その出版を再投入したインスタンスが、次の回の先頭で archive の `ABANDONED` の行を読み戻したときに、出版ごとの ERROR と Counter で 1 回だけ知らせる。
    見落としと、知らせる前の停止は、最古の経過時間の Gauge（`state=exhausted`）の警報で拾う。
    これは `async-observability.md` の DLQ の滞留時間の監視にあたる。
  - 上限を超えて `FAILED` に残った出版は、Gauge（`state=over-limit`）が 0 より大きい間を警報にし、件数が 0 から増えた回だけ ERROR を出す。
    回ごとに ERROR を出すと、運用者が扱うまで `interval` ごとに同じ通知が続くためである（Context）。
  - Gauge は `FAILED` の行を 1 つの問い合わせで回数の上限の内と外に分け、ロックの外ですべてのインスタンスが集計する（`FailedPublicationGauges`）。
    archive の `ABANDONED` は部分索引（`event_publication_archive_abandoned_idx`）で数え、archive の `COMPLETED` の行が増えても重くならないようにする。
    集計の失敗は再投入を止めない。
- ジョブの実行の回数、再投入の件数、再投入した出版の結果、上限で諦めた出版の数の Counter は、SLO がないためアプリケーションのメトリクスを出さないという `async-observability.md` の原則の例外とする。
  運用者がジョブの実行の結果と再投入の件数と成功と失敗を確かめる要求（#108 の user story 4）があるためである。
- 再投入した出版の結果は、再投入したインスタンスが次の回の先頭で、`event_publication` と archive の表を読み戻して数える（`ResubmissionTracker`）。
  2.2.0 は再投入の件数も成功も知らせないため、公開された表の列だけで判定する。
  覚えるのは自分が `RESUBMITTED` へ進めた出版と、その再投入の後の `completion_attempts` だけで、インスタンスの間で二重に数えない。
  `completion_attempts` を増やすのは再投入だけなので、覚えた値より増えた出版と、覚えたまま自分がまた再投入した出版は、前の再投入を `failed` と数える。
  処理中の出版を覚えておく期間は `max(3 × lock-at-most-for, in-flight-staleness + lock-at-most-for + 2 × interval)` である。
- 予期しない実行時の例外も受けて ERROR で記録し、`runs{outcome=failed}` に数える。
  スケジューラの既定のエラーハンドラに例外を漏らすと、ログは出ても `runs` のどの tag も動かず、停止に気づけないためである。
- 手での再投入は、同じイメージを 1 回だけ起動するジョブ（`EventPublicationResubmitOnce`）にする。
  `event-publication.resubmit-once.target`（`all`、`policy:<名前>`、`event-type:<クラス>`）を渡したときだけ動き、Web のサーバーと定期の実行を動かさない。
  ゲートは単一の述語で、`target` が空白なら通常の起動、空白以外なら `false` を含めて手での再投入の求めと読む。
  不正な値は何もせずに起動する状態を作らず、終了コード 1 にする。
  定期のジョブと同じロックを、`lock-at-most-for` を上限に待って取り、待ち時間を見ずに対象の `FAILED` の出版を `max-per-run` 件まで再投入する。
  ロックの中では、定期のジョブと同じく、再投入の前に古い出版を `FAILED` に戻す。
  対象（`target`）は再投入だけを絞り、古い出版を `FAILED` に戻す処理は対象によらずすべての古い出版に掛かる。
  定期の実行を無効にした環境でも、落ちたまま残った出版を運用者の 1 回の起動で回復できるためである。
  同じロックの中なので、定期のジョブの再投入とは重ならず、定期のジョブを動かす環境では次の回が同じ出版を `FAILED` に戻すのと変わらない。
  リスナーは別のスレッドで動くため、再投入した出版の処理が終わるまで待ち、すべて完了したら終了コード 0、それ以外は 1 で JVM を終える。
  終了の前に `applicationTaskExecutor` を閉じ、処理中のリスナーの完了を最大 20 秒待つ。
  executor は DataSource より先に作られ、文脈を閉じるときは後で閉じられるため、文脈を閉じる前に閉じる。
  20 秒は、`spring.lifecycle.timeout-per-shutdown-phase` の 30 秒と ECS の既定の stopTimeout の 30 秒より短い。
  `resubmit-once` の profile と `ResubmitFailedPaymentsRunner` は消す。
- `ABANDONED` の出版を `FAILED` に戻す仕組みは作らず、Runbook の SQL で戻す。
  2.2.0 には戻す API がないためである。
  `ABANDONED` は終端の状態で、`AbandonedEventPublications` は読むことと消すことだけを持ち、`FailedEventPublications.resubmit` は未完了の表の `FAILED` だけを読み、`EventPublicationRepository.markFailed` は archive の行に届かない。
  SQL は `completion_attempts` を残すため、戻した出版は定期のジョブでなく手での 1 回の再投入だけが扱い、また失敗すればすぐに `ABANDONED` に戻る。
  SQL の手順と確かめ方は Runbook の「上限に達した出版の戻し方」にある。
- 落ちたまま残った `PUBLISHED`、`PROCESSING`、`RESUBMITTED` の出版は、Spring Modulith の `EventPublicationRegistry.markStalePublicationsFailed(Staleness)` で `FAILED` に戻す。
  定期のジョブと手での 1 回の再投入が、ロックを取った中で、再投入の前に呼ぶ（`FailedPublicationSweep`）。
  一つの回が古い出版を読んでから `FAILED` にするまでの間、別のインスタンスはロックを取れず、その出版を再投入できない。
  `FAILED` に戻した出版は、同じ回の再投入の候補になる。
  閾値は `EventPublicationResubmissionConfig.Properties.staleness` が返し、`PUBLISHED` は `wait-age`、`PROCESSING` と `RESUBMITTED` は `event-publication.resubmission.in-flight-staleness` にする。
  `PROCESSING` と `RESUBMITTED` に `wait-age` を使わないのは、再投入して処理中の出版を `FAILED` に戻さないためである。
- 組み込みの Staleness Monitor（`StalenessMonitorConfiguration`）は使わない（Context）。
  2.2.0 は `spring.modulith.events.staleness` の閾値のどれかを書いたときだけ、全インスタンスで `markStalePublicationsFailed` をロックの外から呼ぶ定期のタスクを登録する。
  閾値を書くと起動に失敗させる（`EventPublicationResubmissionConfig.StalenessMonitorProperties`）。
  Spring Modulith は閾値に正の値を求めるため、0 を書いて止めることもできない。
- 古い出版を `FAILED` に戻す処理はロックを取れた回だけで動く。
  組み込みの Staleness Monitor は各インスタンスで `check-interval` ごとに動いたが、この処理はクラスタ全体で `interval` あたりおよそ 1 回から 2 回になる。
  ロックを他のインスタンスが持つ回は省かれ、落ちたインスタンスがロックを残すと `lock-at-most-for` まで止まる。
  間隔の設定（`check-interval`）は要らなくなる。
- `in-flight-staleness` は `lock-at-most-for` の 2 倍より長くし、短いと起動に失敗する。
  古い出版を `FAILED` に戻す処理がロックの中で動いても、この検査は要る。
  再投入の時刻を書いてから、executor の空きの待ちはその回の中で `lock-at-most-for` まで、リスナーの処理はロックを放した後も続くためである。
  形式検証で起動時の検査を 1 倍に緩めると、処理中の出版を `FAILED` に戻す（変異 `startup-check-too-weak`）。
  リスナーの処理の上限は別に持たず、1 回の実行の上限としてすでに concurrency-limit の空きを待つ時間を含めている `lock-at-most-for` で代える。
  本番は、`lock-at-most-for`（10 分）の 2 倍に 5 分の余裕を足した `PT25M` にする。
- `wait-age` はリスナーの処理時間より十分に長くする。
- 有効と無効、`wait-age`、`in-flight-staleness`、間隔は環境変数で注入し、方針、1 回の件数、ロックを持つ上限、手での再投入の完了を待つ上限は `application.yaml` の固定値にする（ADR-008）。
- 出版の完了は、リスナーのトランザクションの後の新しいトランザクションで記録する（[ADR-121](ADR-121-record-event-publication-completion-in-interceptor.md)）。
- 2.2 に代わる仕組みがないため、次は自作のまま残す。
  - 指数的な待ち時間（`multiplier`、`max-wait`）：`ResubmissionOptions` は件数（`withBatchSize`、`withMaxInFlight`）、`publication_date` からの最小の経過時間（`withMinAge`）、条件の関数（`withFilter`）だけを持ち、最後の試行からの待ち時間も倍率も持たない。
  - 定期の実行と ShedLock のロック：2.2 で定期に動くのは組み込みの Staleness Monitor だけで、失敗した出版の再投入を定期に動かす仕組みも、複数のインスタンスで排他する仕組みもない。
  - 古い出版を `FAILED` に戻す処理をロックの中で呼ぶこと（`Properties.staleness`、`StalenessMonitorProperties`）：2.2 の Staleness Monitor は排他せずに全インスタンスで動く。
  - 手での 1 回の再投入（`EventPublicationResubmitOnce`）：2.2 の `FailedEventPublications` は再投入するだけで、リスナーの完了を待つことも、結果を終了コードで返すこともしない。
  - リスナーごとの方針の対応づけ（`@ResubmissionPolicy` と `ResubmissionPolicies`）：2.2 はリスナーごとの待ち時間の設定を持たない。
  - メトリクス（`runs`、`resubmissions`、`outcomes`、`exhausted`、`failed`、`failed.oldest.age`）と結果の読み戻し（`ResubmissionTracker`）：2.2 の可観測性はモジュールの境界の trace とイベントの Counter で、出版の状態の件数も再投入の結果も出さず、成功を知らせる hook もない。
- 本番のコードがセッションに状態を残す SQL を使わないことを、`task lint-session-sql` で検査する。
- 起動時の自動再配信は、ADR-001 のとおり使わない。

shared の範囲は、ADR-048 の jOOQ の共通処理に加えて、Spring Modulith のイベント出版のレジストリの再投入と、その方針をリスナーが選ぶ注釈まで広げる。
レジストリは全モジュールが共有する基盤で業務の概念を持たず、jOOQ を使う処理は Persistence Adapter の層（`infrastructure.persistence` と、同じ層に入る `shared` のパッケージ）に置く規則があるためである。

## Consequences

### Positive

- 外部 API の一時的な障害で失敗した出版が、人の操作なしで回復する。
- 同時に再投入するインスタンスが 1 つになり、重複の処理と DB の負荷が増えない。
- 古い出版を `FAILED` に戻す処理と再投入が、全インスタンスで 1 本のロックの下に並ぶ。
  一つの回が古い出版を読んでから `FAILED` にするまでに、別のインスタンスが再投入した出版を、処理中のまま `FAILED` に戻さない（`EventPublicationResubmitterStalenessTest`、形式検証の `InFlightNeverMarkedStale`）。
  古い出版を読む問い合わせも、各インスタンスの `check-interval` ごとから、ロックを取った回だけに減る。
- 接続が直結でも、接続を多重化するプロキシの後ろでも、インスタンスがいくつでも同じ設定で動く。
- ロックの期限を DB の時刻で決めるため、インスタンスの時計のずれで排他が崩れない。
- ジョブは接続を固定せず、`TableWriter` の規則（H1）の例外も要らない。
- リスナーごとに待ち時間を変えられ、長い障害の間の再投入の回数を指数的な待ち時間で抑えられる。
- 上限を超えて失敗した出版が `FAILED` から外れるため、定期のジョブが毎回読む `FAILED` の行に溜まらない。
  諦めの判断は Spring Modulith の設定 1 つで、自前の `AbandonPolicy`、失敗のたびの DB の読み出し、回数を SQL で数える式、ERROR の重複を避ける記録、回収が要らない。
- 手での再投入が定期のジョブと同じロックを取るため、重ねて再投入せず、定期のジョブを止める手順が要らない。
- 手での再投入の結果を終了コードで返すため、ECS の RunTask や Kubernetes の Job の成否で確かめられる。
- 起動時の再配信と違い、デプロイのたびに全インスタンスが同時に再配信しない。

### Negative

- ShedLock の依存と、`modulith.shedlock` の表と Liquibase の changeset が増える。
- ロックを持つ上限は 1 回の実行の最長より長くする必要があり、実行がそれを超えると二つ目のインスタンスが動く。
  `markResubmitted` は `UPDATE ... WHERE STATUS != 'RESUBMITTED'` で原子的に更新するため、二つ目の実行が同じ出版を二重に投入することはない。
  残る害は二つある。
  一つは、二つの実行が executor の枠（`concurrency-limit`）を奪い合うことである。
  もう一つは、先の実行が古い一覧を持つため、再び `FAILED` に戻った出版を回数の上限を超えて再投入しうることである。
  Spring Modulith が比べる回数もその古い一覧の値なので、この出版は失敗しても `ABANDONED` にならずに `FAILED` に残りうる。
  その後は `isDue` が再投入せず、件数の ERROR が知らせる。
- 時間の予算（`lock-at-most-for / 2`）は、最悪の時間の見積もりに基づく。
  決済のリスナーの呼び出しは `payment-gateway` の retry が `base-config: default` で試行 1 回のため、1 件の最悪は connect 1 秒 + read 2 秒の約 3 秒である。
  `concurrency-limit` は 2 で、100 件では約 49 回分の処理を待ち、およそ 2.5 分になる。
  `lock-at-most-for` の 10 分に対して約 4 倍の余裕がある。
  リスナーを増やした利用先では、この余裕は成り立たないことがあり、予算が次の回へ回す。
- インスタンスが落ちてロックを放さないと、ロックを持つ上限（10 分）まで、再投入も古い出版を `FAILED` に戻す処理も止まる。
- ShedLock はロックの行を作ったことをメモリに覚えるため、`modulith.shedlock` の行を手で消すと、再起動まで同じインスタンスがロックを取れない。
- 毎回 `FAILED` の行をすべて読む（待ち時間でも絞られない）ため、待ち時間の中の出版が大量にあると、毎回の読み込みが重くなる。
  `FAILED` の全件読みのメモリの上限は残る。
- 回数の上限は全リスナーに共通で、リスナーごとに変えられない。
  変える必要が出たら、リスナーの ID から回数を引く `AbandonPolicy` の bean を足す（選択肢10）。
- `markAbandoned` のトランザクションが失敗すると、出版は処理中のまま残り、古い出版として `FAILED` に戻る。
  `DefaultEventPublicationRegistry.markFailedOrAbandoned` は `markAbandoned` の戻り値も見ない。
  `completion_attempts` は上限を超えているため、`isDue` はこの出版を再投入せず、Spring Modulith が `AbandonPolicy` をもう一度聞くこともない。
  この出版は `FAILED` に残り、`state=over-limit` の Gauge と件数の ERROR が知らせる。
- 上限に達した出版の ERROR と `exhausted` は、再投入した次の回まで遅れる。
  再投入したインスタンスがその前に落ちると出ないため、最古の経過時間の警報（`state=exhausted`）で拾う。
- 件数の ERROR は、前の件数をインスタンスのメモリに持つため、各インスタンスが同じ増加を 1 つずつ出し、起動の直後の回にも出る。
  扱うまで残っていることは ERROR では分からず、`state=over-limit` の警報に頼る。
- `ABANDONED` の出版を戻す操作は SQL の手作業で、Spring Modulith の表の列を直接書く。
  Spring Modulith が表の形を変えると、Runbook の SQL も直す必要がある。
- 結果の突き合わせは、再投入したインスタンスのメモリの記録に頼る。
  再投入した直後にそのインスタンスが落ちるか再起動すると、その回の結果は数えない。
  結果の判定は次の回まで遅れ、最大で `interval` と処理時間かかる。
- 方針の対応づけは Spring Framework のリスナーの ID の規則を写すため、その規則が変わると方針が `default` に戻る。結合テストが実際の出版の ID と比べて検出する。
- イベントの型の名前を変えた出版は Spring Modulith が読み出しで落とすため、上限に達せず ERROR も出ない。WARN と最古の経過時間の警報で見つける。
- ジョブも Spring Modulith の諦めの判断も失敗の原因を見分けないため、内部の不変条件の違反のデッドレター（ADR-072）も上限まで再投入し、そのたびにリスナーの ERROR が出る。
- 落ちたまま `PROCESSING` や `RESUBMITTED` に残った出版の回復は、最後の出版か再投入から `in-flight-staleness`（本番 25 分）まで遅れる。
  その間、`FAILED` の出版の Gauge と最古の経過時間の警報はその出版を数えない。
- 終了の前の 20 秒の待ちは、手での再投入が自分で終わるときだけ効く。
  完了の待ちの間に ECS が SIGTERM で止めると、文脈は既定の順で閉じ、`DataSource` を閉じた後に executor を待つため、処理中のリスナーは DB に触れずに失敗する。
  リスナーのトランザクションは戻るため出版は失われず、次の再投入の対象に残る。
- executor の空きの待ちかリスナーの処理が `lock-at-most-for` の前提を破り、再投入から `in-flight-staleness` を超えると、古い出版を `FAILED` に戻す処理が処理中の出版を `FAILED` に戻す。
  結果の Counter は、戻した出版を同じ回にどのインスタンスが再投入し直しても、前の再投入を `failed` と数える。
- 最初の配信が `concurrency-limit` の空きを待つ間に `wait-age` を過ぎた出版は、古い出版として `FAILED` に戻り、同じ回の再投入が重ねて再投入しうる。その場合はリスナーの冪等性に頼る。その間、`FAILED` の出版の Gauge は処理中の出版も数える。
- 一つの回が古い出版を読んでから `FAILED` にするまでの間に `lock-at-most-for` を超えて止まると、ロックが切れ、別のインスタンスがその出版を `FAILED` に戻して再投入しうる。
  遅れて届いた `markFailed` は、読んだときの状態も `last_resubmission_date` も確かめないため、処理中の出版を `FAILED` に戻す（形式検証の既知の発見 1）。
  さらに配信が方針の待ち時間より長く続くと、同じ出版のリスナーが同時に二つ動く（既知の発見 2）。
  時間の予算（`lock-at-most-for / 2`）は候補を選ぶのを止めるだけで、古い出版を `FAILED` に戻す処理を止めない。
  回が 10 分止まる必要があるため、組み込みの Staleness Monitor のときより窓は狭く、残りはリスナーの冪等性に頼る。
  戻った出版も、動いていたリスナーが成功すれば、`markCompleted` が状態を見ずに完了にする。
  同じ型の競合は上流でも扱われている（[#1971](https://github.com/spring-projects/spring-modulith/issues/1971)、`markAbandoned` の [#1764](https://github.com/spring-projects/spring-modulith/issues/1764)）。
- 古い出版を `FAILED` に戻す処理は、回ごとに `PUBLISHED`、`PROCESSING`、`RESUBMITTED` の行をすべて読み、その時間は回の時間に入る。
  この処理が DB の失敗で終わると、その回の再投入も動かず、`runs{outcome=failed}` に数える。
- 決済の circuit breaker（`payment-gateway`）が open の間の再投入は、外部を呼ばずに `CallNotPermittedException` で `FAILED` になり、`completion_attempts` を 1 消費する。
  コードで確認したのは次の 3 点である。
  `OrderConfirmedListener.on` と `ChargeOrderCommandHandler` はこの例外を捕まえず（main に捕まえる箇所はない）、`PaymentGatewayClientIntegrationTest.consecutiveFailuresOpenCircuitBreaker` が open のときにこの例外が出て決済代行を呼ばないことを確かめている。
  再投入の予約（`markResubmitted`）が `completion_attempts` を 1 増やす。
  上限の判定は回数だけを見て（`ResubmissionPolicies.isDue`、`abandon-after-attempts`）、失敗の原因を見分けない。
  確認していないのは頻度である。
  open は 30 秒で half-open に移るため、最初の待ち時間（本番想定 5 分）の後の再投入では多くの場合閉じているとみられるが、障害が続くと再び open になり、同じ時刻に再投入された複数の出版が呼び出しなしで回数を消費しうる。
  本番で観測してから `CallNotPermittedException` の扱いを見直す。
- 指数的な待ち時間のため、上限に達するまでの時間が延び（本番の `default` でおよそ 2 時間 20 分）、`failed.oldest.age{state=retrying}` の警報の閾値もそれに合わせて長くなる。

### Neutral

- 本番と STG の環境変数の注入は、ECS のタスク定義を作るときに足す。
- archive の表の保存期間と消し方は #542 で決める（[ADR-120](ADR-120-keep-abandoned-event-publications-out-of-archive-retention.md)）。
- `spring.datasource.hikari.connection-init-sql` の `SET` と HikariCP の `schema`（`search_path`）もセッションの設定であり、接続を多重化するプロキシの後ろでは保たれない。この ADR では扱わず、`lint-session-sql` も検出しない。
- ShedLock の期限の式は、`cast(current_timestamp(6) as timestamp)`（セッションの TimeZone での時刻帯のない現在時刻）で時刻を書き、同じ式で比べる。
  `modulith.shedlock` の `lock_until` は `timestamptz` の列であり、書くときと読むときに同じセッションの TimeZone で変換されるため、接続の TimeZone によらず正しく動く（PostgreSQL 17 で実測）。
  列を `timestamp` にすると壊れるため、変えない。
  `ShedLockSessionTimeZoneTest` がこれを守る。
- ADR-019 の「retry は client adapter の一層だけ」は呼び出しの即時の再試行についての決定であり、分単位の間隔と回数の上限を持つ出版の再投入はそれと別の層である。
  決済の client は試行 1 回なので、1 件の出版あたりの外部の呼び出しは最大 6 回（最初の 1 回と再投入 5 回）である（`PaymentGatewayClient.java:83-84`、`application.yaml:262-263`、`application.yaml:201-205`）。
  `idempotent` の retry を継承すると 6 × 3 = 18 回になるが、再投入の間隔は 5 分から 1 時間なので、即時の連続した呼び出しにはならない。

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
- **Cons**：先の再投入が `PROCESSING` に進めた行は二つ目の更新の条件を満たし、同じ出版を重ねて再投入する。

### 選択肢5: 試行の回数を独自の表に持つ

- **Description**：出版の ID ごとに再投入の回数と通知の済みを記録する表を作る。
- **Pros**：通知の重複を DB で防げる。
- **Cons**：Spring Modulith は回数を `completion_attempts` で持つため二重の管理になる。通知の重複は監視で `event_publication.id` ごとにまとめれば足り、表とマイグレーションを足すほどの効果がない。

### 選択肢6: `ResubmissionOptions` の件数の上限（`LIMIT`）を使う

- **Description**：`withBatchSize` と `withMaxInFlight` で 1 回の件数を SQL で絞る。
- **Pros**：毎回の読み込みが件数の上限で抑えられる。
- **Cons**：`LIMIT` が条件の関数より先に掛かるため、上限に達した古い出版が枠を占め、新しい失敗に届かなくなる。

### 選択肢7: 全体で一律の待ち時間と回数の上限

- **Description**：`wait-age` を全リスナーで共有し、待ち時間を延ばさない。
- **Pros**：設定と注釈が要らない。
- **Cons**：リスナーごとの外部システムの回復時間の違いを表せず、長い障害の間も同じ短い間隔で再投入を繰り返す。

### 選択肢8: 定期のジョブを止めて手での再投入を起動する（main の `resubmit-once` の profile）

- **Description**：手での再投入はロックを取らず、重ねたくなければ先に定期のジョブを止める。
- **Pros**：手での入口のコードが少ない。
- **Cons**：手順を忘れると重ねて再投入する。起動したアプリが Web のサーバーと定期の実行も動かし、リスナーの完了を待たずに終わるかどうかが起動の仕方で変わる。

### 選択肢9: 決済の呼び出しの circuit breaker と retry（または Spring Retry）をイベントのリスナーで使い、再投入のジョブを置かない

- **Description**：リスナーの境界で Resilience4j の circuit breaker と retry（または Spring Retry）を掛け、失敗したら再投入のジョブの代わりにその場の再試行に任せる。
- **Pros**：依存はすでにあり（`spring-cloud-starter-circuitbreaker-resilience4j`）、設定が少なく、短い障害をその場で吸収できる。
- **Cons**：状態がインスタンスのメモリにあり、再起動で消える。
  インスタンスごとに別の状態を持つため、全体の障害を調整できない。
  circuit breaker は呼び出しを拒否するだけで、イベントを保持しない。
  時間の尺が違い、Resilience4j はミリ秒から秒、再投入は分から時間である。

Resilience4j は再投入と補完関係にあり、ADR-019 のとおり client adapter に残す。

### 選択肢10: 自前の `AbandonPolicy` でリスナーごとの回数の上限を持つ

- **Description**：`ResubmissionPolicies` を `AbandonPolicy` の bean にし、方針ごとに `max-resubmissions` を持ち、失敗した出版の回数がそれを超えたら `ABANDON` を返す。
  渡される回数は再投入の前の値なので、DB の行を主キーで読み直すか、状態が `FAILED` なら 1 を足して数える。
- **Pros**：リスナーごとに回数の上限を変えられる。
- **Cons**：main の 2 つの方針はどちらも 5 回で、リスナーごとの回数を使っていない。
  判断と ERROR と Counter を 1 つの bean が持ち、失敗のたびに DB を読むか、Spring Modulith が渡す値の作り方（Context の表）に頼る自作が増える。
  渡される値の作り方に頼る点は `abandon-after-attempts` と同じで、学習テストが変化を捕まえる。

### 選択肢11: 条件の関数の回数の条件を外し、回数の上限を Spring Modulith の諦めの判断だけに任せる

- **Description**：`ResubmissionPolicies.isDue` から回数の条件を外す。失敗を記録しない試行で上限を超えた出版は、もう 1 回再投入し、その失敗で `ABANDONED` にする。上限を超えた `FAILED` を `FailedEventPublications.applyAbandonPolicy` で諦める形も含む。
- **Pros**：回数を見る場所が Spring Modulith の諦めの判断の一か所になる。上限を超えた出版が `FAILED` に残らない。
- **Cons**：形式検証で、停止の後に古い出版として `FAILED` に戻した出版を、ジョブが上限を超えてさらに再投入した（[formal-verify-event-resubmission](../../.agents/skills/formal-verify-event-resubmission/SKILL.md) の `ResubmissionsCapped`）。`applyAbandonPolicy` で諦める形は、`FAILED` の行をすべて読んでイベントを読み戻すため定期のジョブの読み込みが倍になり、遅れた印で `FAILED` に戻った処理中の出版を諦めるため、その配信が成功しても `ABANDONED` のまま残る。

### 選択肢12: 例外の種類を自作で記録し、`AbandonPolicy` が読んで直らない例外をすぐ諦める

- **Description**：リスナーの失敗の例外の種類を出版の ID ごとに表かメモリに記録し、`AbandonPolicy` がそれを読んで、直らない例外なら 1 回目で `ABANDON` を返す。
- **Pros**：直らない例外の出版を、上限まで再投入せずに済む。
- **Cons**：例外の記録の表か、インスタンスをまたげないメモリの仕組みが要り、置き換えで減らした自作が増える。Spring Modulith が例外を渡すようになれば要らなくなる。

### 選択肢13: 組み込みの Staleness Monitor をロックの外で動かし続ける

- **Description**：`spring.modulith.events.staleness` に閾値を書き、Spring Modulith の `StalenessMonitorConfiguration` が全インスタンスで `check-interval` ごとに古い出版を `FAILED` に戻す。
- **Pros**：古い出版を `FAILED` に戻す呼び出しを自作しない。ロックを他のインスタンスが持つ間も動く。
- **Cons**：古い出版を読んでから `FAILED` にするまでの間に別のインスタンスが再投入した出版を、処理中のまま `FAILED` に戻す（Context）。窓はその読み取りと更新の間だけだが、回が長引かなくても開き、リスナーの冪等性に頼るしかない。

### 選択肢14: 手での 1 回の再投入では古い出版を `FAILED` に戻さない

- **Description**：古い出版を `FAILED` に戻す処理を定期のジョブだけで呼び、手での 1 回の再投入は `FAILED` の出版を再投入するだけにする。
- **Pros**：手での 1 回の再投入が、対象（`target`）の外の出版の状態を変えない。
- **Cons**：定期の実行を無効にした環境では、落ちたまま残った出版を戻す手段が SQL の手作業だけになる。`FailedPublicationSweep` に、どちらの入口かを渡す引数が要る。定期のジョブを動かす環境では、次の回が同じ出版を `FAILED` に戻すため、対象の外の出版の状態を変えないという利点は残らない。

## References

- [ADR-001: Spring Modulith によるモジュラーモノリス](ADR-001-adopt-spring-modulith-modular-monolith.md)
- [ADR-006: 絶対時刻を UTC / Instant / timestamptz に統一する](ADR-006-utc-instant-absolute-time-policy.md)
- [ADR-008: application.yaml を単一にし、設定を外部から注入する](ADR-008-single-application-yaml-external-config.md)
- [ADR-015: 可観測性データを構造化し保護する](ADR-015-structure-and-protect-observability-data.md)
- [ADR-019: 外部連携の耐障害性と容量制御を標準化する](ADR-019-define-resilience-and-capacity-guardrails.md)
- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-054: 楽観的ロックの競合を lock_no の条件と更新件数で判定し、業務テーブルの INSERT と UPDATE を集約の宣言から TableWriter が書く](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)
- [ADR-070: SQL の span を jOOQ の ExecuteListener で作り、SQL の文と値を入れない](ADR-070-record-sql-spans-with-jooq-execute-listener.md)
- [ADR-072: 外部システムを本番のコードで偽らず、WireMock のコンテナで偽る](ADR-072-fake-external-systems-with-wiremock.md)
- [ADR-121: イベント出版の完了を、リスナーのトランザクションの後の新しいトランザクションで記録する](ADR-121-record-event-publication-completion-in-interceptor.md)
- [ADR-122: Spring Modulith 2.2.0 を Spring Boot 4.1.2 のまま入れてから、Spring Boot 4.2.0 と Spring Cloud 2026.0.0 へ別の変更で上げる](ADR-122-raise-spring-boot-4-2-after-spring-modulith-2-2.md)
- [ADR-120: archive の表の保持期限を COMPLETED の行だけに掛け、ABANDONED の行は運用者が戻すか消すまで残す](ADR-120-keep-abandoned-event-publications-out-of-archive-retention.md)
- [設計の作り込みの程度と段階的な再構築](../principles/right-sizing.md)
- [非同期処理の失敗時の再試行と回復](../integration/async-failure-recovery.md)
- [非同期処理のトレースと監視](../integration/async-observability.md)
- [PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)
- [イベント出版の再投入のログイベント](../observability/runbook-event-publication-resubmission.md)
- [issue #108](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/108)
- [issue #562](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/562)
- [issue #556](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/556)
- [formal-verify-event-resubmission](../../.agents/skills/formal-verify-event-resubmission/SKILL.md)
- [Spring Modulith 2.2.0 のリリースノート](https://github.com/spring-projects/spring-modulith/releases/tag/2.2.0)
- [Spring Modulith: Abandoning failed publications](https://docs.spring.io/spring-modulith/reference/events.html)
- [JdbcEventPublicationRepositoryV2.java（2.2.0）](https://github.com/spring-projects/spring-modulith/blob/2.2.0/spring-modulith-events/spring-modulith-events-jdbc/src/main/java/org/springframework/modulith/events/jdbc/JdbcEventPublicationRepositoryV2.java)
- [DefaultEventPublicationRegistry.java（2.2.0）](https://github.com/spring-projects/spring-modulith/blob/2.2.0/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/core/DefaultEventPublicationRegistry.java)
- [StalenessMonitorConfiguration.java（2.2.0）](https://github.com/spring-projects/spring-modulith/blob/2.2.0/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/config/StalenessMonitorConfiguration.java)
- [StalenessProperties.java（2.2.0）](https://github.com/spring-projects/spring-modulith/blob/2.2.0/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/config/StalenessProperties.java)
- [AbandonPolicy.java（2.2.0）](https://github.com/spring-projects/spring-modulith/blob/2.2.0/spring-modulith-events/spring-modulith-events-api/src/main/java/org/springframework/modulith/events/AbandonPolicy.java)
- [CompletionRegisteringAdvisor.java（2.2.0）](https://github.com/spring-projects/spring-modulith/blob/2.2.0/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/support/CompletionRegisteringAdvisor.java)
- [ApplicationListenerMethodAdapter.java（Spring Framework 7.0.9）](https://github.com/spring-projects/spring-framework/blob/v7.0.9/spring-context/src/main/java/org/springframework/context/event/ApplicationListenerMethodAdapter.java)
- [ShedLock 7.10.1](https://github.com/lukas-krecan/ShedLock/tree/shedlock-parent-7.10.1)
- [PgBouncer: Features（SQL feature map for pooling modes）](https://www.pgbouncer.org/features.html)
