---
type: Convention
title: 非同期処理の失敗時の再試行と回復
description: 非同期処理が失敗したときの再試行、Spring Modulith のイベント出版の状態と再投入、DLQ に当たる ABANDONED への退避の道、DLQ からの再実行、失敗メッセージの保持、複数のシステムにまたがる整合性の回復方法を定める。コンシューマーのエラー処理を実装するとき、失敗時の運用を設計するときに読む。
tags: [convention, integration, async, resilience, future-arch-guidelines]
---

# 非同期処理の失敗時の再試行と回復

一時的なエラーは再試行し、回復不能なエラーは業務の結果として記録して正常に終えるか、`ABANDONED` にして人の確認に回す。
DLQ に当たるのは Spring Modulith の `ABANDONED` の出版で、アプリケーションが DLQ へ直接送るコードは書かず、諦める判断は `abandon-after-attempts`（方針ごとの値と全体の上限）に集める。
再実行は Runbook の手順で `ABANDONED` の出版を `FAILED` に戻して手での 1 回の再投入に一本化し、複数のシステムにまたがる不整合は補償トランザクションより先に設計で減らす。

用語は[メッセージングの設計](async-messaging-design.md)の定義に従う。

## 再試行

エラーを次の二つに分けて扱う。

- **回復可能なエラー**：外部 API のタイムアウトなど、一時的な障害で、時間をおけば成功が見込めるエラー。
- **回復不能なエラー**：内部ロジックのエラーやメッセージ自体の検証エラーなど、何度再試行しても成功しないエラー。

回復可能なエラーは、回数と間隔を決めて再試行する。

- 外部呼び出しを再試行する層は、イベント出版の再投入だけにする（[外部システムの Client](../backend/class-roles/external-client.md)）。
- メッセージの再配信の間隔は、基盤が指数バックオフをサポートしていれば使う。指数バックオフに独自の実装が必要な場合は、実装費用に見合う効果がある場合だけ採用する。

回復不能なエラーは、資源の浪費と他の処理への影響を避けるため、次の三つに分けて扱う。

- **業務の結果として記録して正常に終えるもの**：業務上の拒否、契約の不備、リスナーが動く前に業務の状態が変わった場合。出版は `COMPLETED` になり、再投入しない。
- **最初の失敗で `ABANDONED` にするもの**：冪等キーを持たない外部システムを呼ぶリスナー。方針の `abandon-after-attempts: 1` で、リスナー単位で諦める。例外の種類では分けない。
- **上限まで再投入して `ABANDONED` にするもの**：上のどちらでもない予期しない例外。内部の不変条件の違反を含む。Spring Modulith 2.2 の `AbandonPolicy` は失敗の例外を受け取らないため、直らない例外も上限まで再投入する（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。

リスナーごとの失敗の原因と、戻すかどうかの判断は、Runbook の[リスナーごとの失敗の原因と直し方](../observability/runbook-event-publication-resubmission.md#リスナーごとの失敗の原因と直し方)にある。

## Spring Modulith のイベントの失敗

外部ブローカーを導入するまでは、リスナーが失敗したイベント出版を、レジストリが次の二つに分けて持つ。

- **再投入を待つ `FAILED`**：回数の上限に達しておらず、定期のジョブが再投入する。
- **DLQ に当たる `ABANDONED`**：回数の上限まで再投入しても失敗したか、方針の `abandon-after-attempts: 1` で最初の失敗で諦めた。archive の表に移り、人の確認を待つ。

失敗した出版の再投入は `FailedEventPublications` を使い、落ちたまま残った出版は古い出版として `FAILED` に戻してから同じ再投入で扱う。

Spring Modulith 2.2.0 のイベント出版は、次の状態を持つ（[Spring Modulith: Event Publication Lifecycle](https://docs.spring.io/spring-modulith/reference/events.html)）。

- **`PUBLISHED`**：記録され、リスナーの実行を待っている。
- **`PROCESSING`**：リスナーが実行している。
- **`COMPLETED`**：リスナーが成功した。
- **`FAILED`**：リスナーが例外を送出した、または古い出版を `FAILED` に戻す処理（`StalePublications`）が古いと判定した。
- **`RESUBMITTED`**：失敗した出版を再投入し、実行を待っている。
- **`ABANDONED`**：リスナーが例外を送出し、`AbandonPolicy` か `abandon-after-attempts` の回数で諦めると判断した。再投入と、古い出版を `FAILED` に戻す処理の対象にならない。`completion-mode: archive` では archive の表へ移る。

出版は試行の回数（`completion_attempts`）も持つ。
公式のリファレンスは `PROCESSING` に移るときに増えると書くが、2.2.0 の JDBC の実装は、出版を作るときに 1 を入れ、再投入で `RESUBMITTED` にするたびに 1 増やし、`PROCESSING` に移るときには増やさない。
このため値は、リスナーを呼んだ回数ではなく、作成と再投入の予約の回数であり、1 を引くと再投入の回数になる。
再投入したかどうかは `completion_attempts` で見る。
2.2.0 の JDBC の実装は、作成時の `last_resubmission_date` に `publication_date` と同じ値を入れるためである（[issue #108 の確認結果](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/108#issuecomment-6032559650)）。

再投入と回復は次のように扱う。

- 失敗した出版の再投入は、`FailedEventPublications.resubmit(ResubmissionOptions)` を使う。
  `ResubmissionOptions` で、一度に扱う件数、同時に実行する件数、最小の経過時間、条件（イベントの型、`completionAttempts` の上限）を付ける。
  回数の上限を超えた出版は、`spring.modulith.events.resubmission.abandon-after-attempts` で `ABANDONED` にして再投入の対象から外し、人の確認に回す。
  `resubmit` は再投入した件数を返さない。
  条件の関数は `RESUBMITTED` への更新より前に呼ばれ、更新できなかった出版でも呼ばれるため、そこで数えた値は再投入の候補の件数であり、再投入した件数ではない。
  定期の再投入は、再投入のあとに `event_publication` と archive の表を読み戻し、実際に `RESUBMITTED` へ進んだ出版の件数を `event.publication.resubmissions` に記録する（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
- 古い出版は、`StalePublications` の 1 文の UPDATE で、完了していない（`completion_date IS NULL`）`PUBLISHED`、`PROCESSING`、`RESUBMITTED` の出版のうち、最後の出版か再投入の時刻（`last_resubmission_date`、なければ `publication_date`）から状態ごとの時間を過ぎたものを `FAILED` にする。
  状態と時刻は更新と同じ時点で確かめるため、読んだ後に別のインスタンスが再投入した出版は `FAILED` に戻らない。
  測り方は Spring Modulith 2.2.0 の `markStalePublicationsFailed`（`DefaultEventPublicationRegistry`）と同じで、その状態に入ってからの時間では測らないため、`PROCESSING` の時間は executor の空きを待つ時間も含む。
  Spring Modulith の組み込みの Staleness Monitor（`spring.modulith.events.staleness.*`）と `markStalePublicationsFailed` は使わず、閾値を書くと起動に失敗する。
  代わりに、定期の再投入と手での 1 回の再投入が、ShedLock のロックの中で、再投入の前に更新する（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
  `PUBLISHED` の時間は定期の再投入の最初の待ち時間（`wait-age`）と同じにする。
  `PROCESSING` と `RESUBMITTED` の時間は `in-flight-staleness` にし、`lock-at-most-for` の 2 倍より長いことを起動時に検証する。
  その代わり、落ちたまま `PROCESSING` や `RESUBMITTED` に残った出版の回復は、最後の出版か再投入から `in-flight-staleness` を過ぎ、定期の再投入がロックを取る回まで遅れる。
  ただし、最初の配信が `concurrency-limit` の空きを待つ間に待ち時間を過ぎた出版は、`FAILED` に戻った回で重ねて再投入しうる。
  その場合はリスナーの冪等性で結果を保つ。
  定期の再投入を無効にすると、古い出版を `FAILED` に戻す処理も止まる。
- `IncompleteEventPublications` は、処理中のものを含めて、完了していない出版をすべて対象にする。
  古い出版を `FAILED` に戻してから再投入するため、落ちたまま残った出版の回復にも使わない。
- 起動時の自動再配信（`republish-outstanding-events-on-restart`）は使わない（[ADR-001](../adr/ADR-001-adopt-spring-modulith-modular-monolith.md)）。
- 回復不能と判定したエラーは、ステータス管理テーブルを FAILED にして失敗の内容を記録し、リスナーを正常終了させて再投入の対象から外す（[非同期処理のステータス管理](async-job-status.md)）。
  このときイベント出版は `COMPLETED` になり、ステータス管理テーブルの FAILED は出版の `FAILED` とは別の値である。
- 予期しない例外はリスナーから送出し、イベント出版を `FAILED` で残す。
- 外部システムの呼び出しの結果は、一時障害、業務上の拒否、資格情報の不備、契約の不備に分ける（[ADR-072](../adr/ADR-072-fake-external-systems-with-wiremock.md)）。
  再投入で回復できる一時障害と資格情報の不備は例外にして出版を `FAILED` に残し、業務上の拒否と契約の不備は業務の状態に記録してリスナーを正常終了させる。
  契約に合わない応答（想定外の状態のコード、本文がない、未知の状態）も契約の不備に含める。
  分類の詳細は[外部システムの Client](../backend/class-roles/external-client.md)に従う。
  この基準は、失敗の前に書いた行とイベントの出版を commit してよいかで決める、[例外と値の使い分け](../backend/class-roles/business-exception.md)と同じである。
- 出版の `FAILED` に残すのは、再投入で回復できる失敗と、内部の不変条件の違反である。
  内部の不変条件の違反は、確定のイベントを受けたのに注文がないような、不具合でしか起きない状態である。
  業務の状態に記録すると不具合が隠れるため、例外を投げ、上限まで再投入した後に `ABANDONED`（DLQ）へ意図して残す。
  出版はイベントを保ち、ERROR のログが運用者に届き、不具合を直したあとに再投入できる。
  定期の再投入は失敗の原因を見分けないため、この出版も回数の上限まで再投入する（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
- リスナーが動く前に業務の状態が変わったとき（確定のあとに取り消した注文）は、業務の結果として処理せずに正常終了させる。
  再投入しても結果は変わらないため、`FAILED` に残さない。

失敗した出版は、定期のジョブ（`shared.eventresubmission` の `EventPublicationResubmitter`）が、リスナーの方針の待ち時間を過ぎたものを回数の上限まで自動で再投入する（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
方針はリスナーの `on` に付ける `@ResubmissionPolicy`（`shared.events`）で選び、付けなければ `default` になる。
n 回目の再投入の前に、最後の試行から `min(wait-age × multiplier^(n-1), max-wait)` を待つ。
ジョブは ShedLock のロックで、同時に 1 つのインスタンスだけが動く。
上限の回数目の再投入でまた失敗した出版は、Spring Modulith が `abandon-after-attempts` で `ABANDONED` にし、定期のジョブが出版ごとの ERROR のログで知らせる。
運用者は、`FAILED` に戻して手での 1 回の再投入（`EventPublicationResubmitOnce`）で再投入するか片付ける。
調べ方と手順は[イベント出版の再投入のログイベント](../observability/runbook-event-publication-resubmission.md)にある。
再投入の検証は、決済の `PaymentGatewayClientIntegrationTest` が、本番の Client と手での 1 回の再投入で、一時障害の `FAILED` から試行の回数 2 の `COMPLETED` になり決済記録が 1 件になることを確かめる。
`completion-mode: archive` で完了した出版を移す archive の表は、`COMPLETED` の行を 7 日で消し、`ABANDONED` の行を残す（[ADR-120](../adr/ADR-120-keep-abandoned-event-publications-out-of-archive-retention.md)、[改廃の方法](../database/table-retention-and-personal-data.md#改廃の方法)）。

## DLQ

DLQ に当たるのは `ABANDONED` の出版である。
`completion-mode: archive` のため archive の表（`modulith.event_publication_archive`）に `status = 'ABANDONED'` で入り、イベントとリスナーの ID を保つ。
外部ブローカーを導入するまで DLQ は一つで、ソースキューごとには分けない。リスナーは `listener_id` で見分ける。

### DLQ への退避

アプリケーションが DLQ へ直接送るコードは書かない。
退避は Spring Modulith の `AbandonPolicy`（`ResubmissionPolicies`）に集め、道は次の二つである（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。

- **リスナー単位の即時の退避**：方針の `abandon-after-attempts: 1`。最初の配信の失敗で `ABANDONED` にし、自動では再投入しない。冪等キーを持たない外部システムを呼ぶリスナーが、二重の呼び出しを避けるために使う。
- **回数の上限での退避**：全体の `spring.modulith.events.resubmission.abandon-after-attempts`（main では 5）か、2 以上の方針の値。その回数目の再投入の失敗で `ABANDONED` にする。

どちらも失敗の例外の種類を見ない。
厳密な順序が必要なキューには適用しない（[順序を守るキューの回復不能エラー](async-ordering-and-idempotency.md#順序を守るキューの回復不能エラー)）。

### DLQ からの再実行

`ABANDONED` の出版は、定期の再投入、手での 1 回の再投入、古い出版を `FAILED` に戻す処理のどれの対象にもならない。
Spring Modulith 2.2.0 には `ABANDONED` を `FAILED` に戻す API がないため、Runbook の「上限に達した出版の戻し方」の SQL で `FAILED` に戻し、「手での再投入」で再投入する。
戻す前に、原因ごとの直し方と、戻すと外部の呼び出しが繰り返されるかを、Runbook の[リスナーごとの失敗の原因と直し方](../observability/runbook-event-publication-resubmission.md#リスナーごとの失敗の原因と直し方)で確かめる。
再実行の操作は手での 1 回の再投入に統一し、コンシューマーに DLQ を直接読ませない。

### 失敗メッセージの保持

`ABANDONED` の行は、archive の保持期限の対象にせず、戻すか消すまで残す（[ADR-120](../adr/ADR-120-keep-abandoned-event-publications-out-of-archive-retention.md)）。
出版がイベントを保つため、メッセージの内容を別の場所へ複製しない。

## 複数のシステムにまたがる整合性

自社 DB と外部サービスへ順に登録する処理の後段で回復不能なエラーが起きると、不整合が残る。
回復の方式は次の二つである。

- **Saga**：失敗したら、それまでの処理を取り消す補償トランザクションを逆順に実行する。取り消し API が必要で、副作用が残る場合がある。補償トランザクションも非同期の処理として切り出す。
- **手動リカバリ**：失敗したら通知し、不整合を一時的に許容して、運用担当者がデータの修正で回復する。

方式を選ぶ前に、設計で非同期の箇所を減らせないかを検討する。
例として、売上に直結する決済サービスへの登録までを同期で行い、遅れても影響の小さい顧客管理への連携だけを非同期にすれば、失敗しても後から登録し直す一方向の回復で済む。

- 在庫引当のように、失敗を放置すると業務上許容できない損失になる場合だけ Saga を採用する。
- 取り消し API を用意しにくい場合や発生頻度が低い場合は、手動リカバリで様子を見る。送信済みのメールのように取り消せない副作用は、訂正の連絡など別の処理で打ち消す。
- どちらの方式でも、再試行を組み込んで不整合の発生頻度を下げる。

## 出典

- フューチャー株式会社「非同期設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forAsync/async_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
