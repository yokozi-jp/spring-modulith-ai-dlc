---
type: Runbook
title: イベント出版の再投入のログイベント
description: 失敗したイベント出版の定期の再投入（EventPublicationResubmitter）が出す ERROR と WARN の運用定義と、上限に達した出版の調べ方、手での再投入、片付け、自動の再投入の停止、メトリクスの見方を定める。そのログやメトリクスに対応するとき、再投入の設定を変えるときに使う。
tags: [runbook, observability, logging, operations, integration, spring-modulith]
---

# イベント出版の再投入のログイベント

定期のジョブは、リスナーの失敗で `FAILED` に残ったイベント出版を、待ち時間の後に上限の回数まで再投入する（[ADR-075](../adr/ADR-075-resubmit-failed-event-publications-periodically-with-advisory-lock.md)）。
上限に達した出版は出版ごとの `ERROR` で知らせ、その後は運用者がこの文書の手順で扱う。
運用定義の項目は[運用者が対応するログイベントの管理](log-operational-events.md)に従う。
`Event publications resubmitted`（`INFO`）と `Event publication resubmission skipped`（`DEBUG`）は運用定義を要求しないため書かない。

## Event publication resubmission exhausted

- **event 名**：`Event publication resubmission exhausted`
- **レベル**：`ERROR`
- **事象**：再投入の回数が上限（`event-publication.resubmission.max-resubmissions`）に達した出版を、そのインスタンスで初めて見つけ、自動の再投入の対象から外した。
- **属性**：`event_publication.id`、`event_publication.event_type`、`event_publication.listener_id`、`event_publication.completion_attempts`
- **発生原因**：外部システムの障害が待ち時間と上限の回数を超えて続いた。
  リスナーの不具合や内部の不変条件の違反（ADR-072 のデッドレター）で、何度動かしても失敗する。
  リスナーの名前を変えたデプロイで、古い出版のリスナーが見つからない。
- **対応**：「上限に達した出版の調べ方」の SQL で出版とリスナーを特定し、リスナーの ERROR のログと trace で原因を確かめる。
  原因を直したら「手での再投入」で再投入し、再投入しないと決めた出版は「再投入しない出版の片付け」で消す。
  同じ出版の ERROR は、ロックを取ったインスタンスの数と再起動の回数だけ重なりうるので、`event_publication.id` でまとめて扱う。

## Event publication resubmission failed

- **event 名**：`Event publication resubmission failed`
- **レベル**：`WARN`
- **事象**：ジョブの 1 回の実行が DB の例外で失敗し、その回の再投入をしなかった。
- **属性**：`exception.type`、`exception.message`、`exception.stacktrace`
- **発生原因**：接続の pool の枯渇（`connection-timeout`）、DB の停止、`statement_timeout`、`pg_advisory_unlock` が偽を返した（このセッションがロックを持っていない）。
- **対応**：次の回で再試行するため、1 回だけなら対応しない。
  続くときは自動の回復が止まっているので、当日中に DB の状態と接続の pool を確かめる。
  `pg_advisory_unlock` の失敗が続き、他のインスタンスが `event.publication.resubmission.runs{outcome=skipped}` だけを数えるときは、ロックを持つ接続が pool から退く（HikariCP の `max-lifetime`、既定 30 分）まで待つか、そのインスタンスを再起動する。

## 上限に達した出版の調べ方

`:max_resubmissions` は `application.yaml` の `event-publication.resubmission.max-resubmissions`（main では 5）にする。
Spring Modulith は最初の失敗の時刻を持たないため、`publication_date`（最初の試行を始めた時刻）で代える。
archive の表には完了した出版だけがあり、上限に達した出版は入らない。

```sql
-- 上限に達した出版の一覧
SELECT id,
       event_type,
       listener_id,
       publication_date      AS first_attempted_at,
       last_resubmission_date AS last_attempted_at,
       completion_attempts,
       completion_attempts - 1 AS resubmissions
  FROM modulith.event_publication
 WHERE status = 'FAILED'
   AND completion_attempts > :max_resubmissions
 ORDER BY publication_date;

-- イベントの型とリスナーごとの件数と最古の時刻
SELECT event_type, listener_id, count(*) AS publications, min(publication_date) AS oldest_first_attempted_at
  FROM modulith.event_publication
 WHERE status = 'FAILED'
   AND completion_attempts > :max_resubmissions
 GROUP BY event_type, listener_id
 ORDER BY oldest_first_attempted_at;
```

## 手での再投入

上限に達した出版は、自動では再投入しない。
原因を直したあとに `OrderConfirmed` の失敗した出版を再投入するときは、`resubmit-once` の profile でアプリを 1 つ起動する（`ResubmitFailedPaymentsRunner`）。
この入口は advisory lock を取らないため、定期のジョブと同時に動くと同じ出版を重ねて再投入しうる。
リスナーは冪等なので結果は変わらないが、重ねたくないときは先に「自動の再投入の停止」の手順で定期のジョブを止め、再投入の後に戻す。

## 再投入しない出版の片付け

再投入しないと決めた出版（消した注文を指す出版、名前を変えたリスナーの古い出版など）は、`id` を指定して未完了の表から消す。
消す前に、上の一覧の SQL の結果を調査の記録に残す。

```sql
DELETE FROM modulith.event_publication
 WHERE id = :id
   AND status = 'FAILED';
```

片付けたあとは、`event.publication.failed{state=exhausted}` が減ったことで確かめる。

## 自動の再投入の停止

環境変数 `EVENT_PUBLICATION_RESUBMISSION_ENABLED` を `false` にしてデプロイする。
定期の実行と Spring Modulith の Staleness Monitor が一緒に止まり、落ちたまま `PUBLISHED`、`PROCESSING`、`RESUBMITTED` に残った出版も `FAILED` に戻らなくなる。
再開するときは `true` に戻してデプロイする。

## メトリクスの見方

| 名前                                           | 見方                                                                                                                          |
| ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `event.publication.resubmission.runs{outcome}` | `completed` はロックを取って最後まで動いた回、`skipped` は他のインスタンスがロックを持っていた回、`failed` は WARN の回である |
| `event.publication.resubmissions`              | 再投入の候補にした出版の数。手での再投入と同時に動いた場合を除き、再投入した数と一致する                                      |
| `event.publication.failed{state}`              | `retrying` は自動の再投入を待つ `FAILED` の出版、`exhausted` は上限に達した出版の数である                                     |
| `event.publication.failed.oldest.age{state}`   | 各状態で最も古い出版の `publication_date` からの経過秒数である                                                                |

- Gauge はすべてのインスタンスが毎回同じ値を集計して出すため、インスタンスの間の最大値で見る。
- 定期の実行を無効にした環境では更新されず、0 のままである。
- `failed{state=retrying}` は、`concurrency-limit` の空きを待つ間に Staleness Monitor が `FAILED` に戻した処理中の出版も数えうるため、実際の失敗より多く出ることがある。
- `failed{state=exhausted}` の件数は、残っている件数の表示と片付けたあとの確認に使い、警報にしない。
  上限に達した出版を知る主な通知は ERROR のログの監視である。
- `failed.oldest.age{state=exhausted}` が 1 日を超えたら通知する。
  ERROR はインスタンスごとに 1 回しか出ないため、見落とした出版が残り続けるのをこの警報で拾う。
  通知を受けたら「Event publication resubmission exhausted」の節の手順で片付ける。
- `failed.oldest.age{state=retrying}` にも警報を置く（次の節）。

## イベントの型の名前を変えた出版

イベントのクラスの名前やパッケージを変えたデプロイの後、古い出版は Spring Modulith が読み出しで落とし、毎回 WARN `Event '...' of unknown type '...' found` を記録する。
`completion_attempts` が増えないため上限に達せず、ERROR も出ない。
その出版は `failed{state=retrying}` に残り、`failed.oldest.age{state=retrying}` が伸び続ける。

`failed.oldest.age{state=retrying}` の警報の閾値は、待ち時間 ×（上限 + 1）+ 間隔 ×（上限 + 1）を目安にし、本番の値（待ち時間 5 分、間隔 1 分、上限 5 回）でおよそ 40 分にする。
警報が出たら、WARN の `unknown type` と次の SQL で古い型の出版を特定する。

```sql
SELECT event_type, listener_id, count(*) AS publications, min(publication_date) AS oldest_published_at
  FROM modulith.event_publication
 WHERE status = 'FAILED'
 GROUP BY event_type, listener_id
 ORDER BY oldest_published_at;
```

今のコードにない `event_type` の出版は、新しい型へ移す要否を決め、移さないものは「再投入しない出版の片付け」の手順で消す。
