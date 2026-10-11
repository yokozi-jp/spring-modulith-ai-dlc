---
type: Runbook
title: イベント出版の再投入のログイベント
description: 失敗したイベント出版の定期の再投入（EventPublicationResubmitter）と手での 1 回の再投入（EventPublicationResubmitOnce）が出す ERROR と WARN の運用定義と、上限に達した出版の調べ方、手での再投入、片付け、自動の再投入の停止、メトリクスと警報の見方を定める。そのログやメトリクスに対応するとき、再投入の設定を変えるときに使う。
tags: [runbook, observability, logging, operations, integration, spring-modulith]
---

# イベント出版の再投入のログイベント

定期のジョブは、リスナーの失敗で `FAILED` に残ったイベント出版を、リスナーの方針の待ち時間の後に、方針の上限の回数まで再投入する（[ADR-075](../adr/ADR-075-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
上限に達した出版は出版ごとの `ERROR` で知らせ、その後は運用者がこの文書の手順で扱う。
運用定義の項目は[運用者が対応するログイベントの管理](log-operational-events.md)に従う。
`Event publications resubmitted` と `Event publications resubmitted once`（`INFO`）、`Event publication resubmission skipped`（`DEBUG`）は運用定義を要求しないため書かない。

## リスナーの方針

方針は `application.yaml` の `event-publication.resubmission.policies.<名前>` にあり、`max-resubmissions`（上限の回数）、`multiplier`（倍率）、`max-wait`（待ち時間の上限）を持つ。
リスナーの `on` に付けた `@ResubmissionPolicy("<名前>")` で選び、付けないリスナーは `default` になる。
main では `payment.application.OrderConfirmedListener` が `payment` を使い、`default` と `payment` はどちらも 5 回、2 倍、1 時間である。

n 回目の再投入の前に、最後の試行から `min(wait-age × multiplier^(n-1), max-wait)` を待つ。
`wait-age` は環境変数 `EVENT_PUBLICATION_RESUBMISSION_WAIT_AGE` である。

出版の `listener_id` は、リスナーのメソッドの完全修飾名と引数の型である（例：`com.example.demo.payment.application.OrderConfirmedListener.on(com.example.demo.ordering.OrderConfirmed)`）。
次の節の SQL は、`policy` の行にリスナーの ID と方針の上限を並べ、並べないリスナーを `default` の上限で扱う。

## Event publication resubmission exhausted

- **event 名**：`Event publication resubmission exhausted`
- **レベル**：`ERROR`
- **事象**：再投入の回数がリスナーの方針の上限（`max-resubmissions`）に達した出版を、そのインスタンスで初めて見つけ、自動の再投入の対象から外した。
- **属性**：`event_publication.id`、`event_publication.event_type`、`event_publication.listener_id`、`event_publication.completion_attempts`
- **発生原因**：外部システムの障害が方針の待ち時間と上限の回数を超えて続いた。
  リスナーの不具合や内部の不変条件の違反（ADR-072 のデッドレター）で、何度動かしても失敗する。
  リスナーの名前を変えたデプロイで、古い出版のリスナーが見つからない。
- **対応**：「上限に達した出版の調べ方」の SQL で出版とリスナーを特定し、リスナーの ERROR のログと trace で原因を確かめる。
  原因を直したら「手での再投入」で再投入し、再投入しないと決めた出版は「再投入しない出版の片付け」で消す。
- **通知**：同じ出版の ERROR は、ロックを取ったインスタンスの数と再起動の回数だけ重なりうる。
  監視の通知は `event_publication.id` でまとめ、同じ ID は 1 日に 1 回だけ知らせる。

## Event publication resubmission failed

- **event 名**：`Event publication resubmission failed`
- **レベル**：`WARN`
- **事象**：定期のジョブの 1 回の実行が DB の例外かロックの例外で失敗し、その回の再投入をしなかった。
- **属性**：`exception.type`、`exception.message`、`exception.stacktrace`
- **発生原因**：接続の pool の枯渇（`connection-timeout`）、DB の停止、`statement_timeout`、`modulith.shedlock` の読み書きの失敗（ShedLock の `LockException`）。
- **対応**：次の回で再試行するため、1 回だけなら対応しない。
  続くときは自動の回復が止まっているので、当日中に DB の状態と接続の pool を確かめる。

## Event publication resubmit-once incomplete

- **event 名**：`Event publication resubmit-once incomplete`
- **レベル**：`WARN`
- **事象**：手での 1 回の再投入で、再投入した出版の一部が完了しなかったか、`max-per-run` を超えて再投入しなかった出版が残った。終了コードは 1 である。
- **属性**：`event_publication.resubmitted.count`、`event_publication.completed.count`、`event_publication.incomplete.count`、`event_publication.remaining.count`
- **発生原因**：原因が直っておらずリスナーがまた失敗した（`incomplete`）。
  リスナーが `completion-timeout`（10 分）を過ぎても終わらない（`incomplete`）。
  対象が `max-per-run`（100 件）より多い（`remaining`）。
- **対応**：`incomplete` が 0 でなければ、リスナーの ERROR で原因を確かめ、直してから起動し直す。
  `remaining` だけが 0 でなければ、同じ対象でもう一度起動する。
  運用者が起動したジョブの結果なので、監視の通知は要らない。

## Event publication resubmit-once failed

- **event 名**：`Event publication resubmit-once failed`
- **レベル**：`ERROR`
- **事象**：手での 1 回の再投入が例外で終わり、再投入しなかったか、途中で止まった。終了コードは 1 である。
- **属性**：`exception.type`、`exception.message`、`exception.stacktrace`
- **発生原因**：対象の指定が不正（`IllegalArgumentException`）。
  定期のジョブがロックを `lock-at-most-for`（10 分）より長く持った（`lock event-publication-resubmission was not acquired`）。
  DB の停止や接続の失敗。
- **対応**：例外のメッセージで原因を確かめ、指定を直すか DB を確かめてから起動し直す。
  ロックを取れないときは、`event.publication.resubmission.runs{outcome=completed}` が増えているかで定期のジョブが動いていることを確かめ、`modulith.shedlock` の `locked_by` と `lock_until` を読む。
  ShedLock は行を作ったことをメモリに覚えるため、行を消さない。
  運用者が起動したジョブの結果なので、監視の通知は要らない。

## 上限に達した出版の調べ方

Spring Modulith は最初の失敗の時刻を持たないため、`publication_date`（最初の試行を始めた時刻）で代える。
archive の表には完了した出版だけがあり、上限に達した出版は入らない。
`policy` の行と `:default_max_resubmissions`（main では 5）は、`application.yaml` の方針と `@ResubmissionPolicy` に合わせる。

```sql
-- 上限に達した出版の一覧
WITH policy (listener_id, max_resubmissions) AS (
  VALUES ('com.example.demo.payment.application.OrderConfirmedListener.on(com.example.demo.ordering.OrderConfirmed)', 5)
)
SELECT p.id,
       p.event_type,
       p.listener_id,
       p.publication_date       AS first_attempted_at,
       p.last_resubmission_date AS last_attempted_at,
       p.completion_attempts,
       p.completion_attempts - 1 AS resubmissions
  FROM modulith.event_publication p
  LEFT JOIN policy USING (listener_id)
 WHERE p.status = 'FAILED'
   AND p.completion_attempts > coalesce(policy.max_resubmissions, :default_max_resubmissions)
 ORDER BY p.publication_date;

-- イベントの型とリスナーごとの件数と最古の時刻
WITH policy (listener_id, max_resubmissions) AS (
  VALUES ('com.example.demo.payment.application.OrderConfirmedListener.on(com.example.demo.ordering.OrderConfirmed)', 5)
)
SELECT p.event_type, p.listener_id, count(*) AS publications, min(p.publication_date) AS oldest_first_attempted_at
  FROM modulith.event_publication p
  LEFT JOIN policy USING (listener_id)
 WHERE p.status = 'FAILED'
   AND p.completion_attempts > coalesce(policy.max_resubmissions, :default_max_resubmissions)
 GROUP BY p.event_type, p.listener_id
 ORDER BY oldest_first_attempted_at;
```

## 手での再投入

上限に達した出版は、自動では再投入しない。
原因を直したら、同じイメージを 1 回だけ起動し、環境変数 `EVENT_PUBLICATION_RESUBMIT_ONCE_TARGET` に対象を渡す。
対象は次のいずれかである。

- **`all`**：すべての `FAILED` の出版。
- **`policy:<名前>`**：その方針のリスナーの出版（例：`policy:payment`）。
- **`event-type:<クラス>`**：そのイベントの出版（例：`event-type:com.example.demo.ordering.OrderConfirmed`）。

対象を渡すと、アプリは Web のサーバーと定期の実行（Staleness Monitor を含む）を動かさない。
定期のジョブと同じロックを取るため、定期のジョブを止めずに起動してよい。
待ち時間と上限の回数を見ずに、対象の `FAILED` の出版を `max-per-run`（100 件）まで再投入し、リスナーの処理が終わるまで待ってから終わる。
すべて完了したら終了コード 0、それ以外は 1 である。

ローカルでは Compose の `backend` サービスで起動する。

```bash
docker compose --env-file .env -f docker/compose.yml --profile backend run --rm \
  -e EVENT_PUBLICATION_RESUBMIT_ONCE_TARGET=policy:payment backend
```

本番と STG では、同じタスク定義を ECS の RunTask で 1 回だけ動かすか、Kubernetes の Job にし、環境変数を足す。
起動した後は、`Event publications resubmitted once` の `INFO` の件数と終了コードで結果を確かめる。

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
| `event.publication.resubmissions`              | 定期のジョブが再投入の候補にした出版の数。ロックの中で数えるため、再投入した数と一致する                                      |
| `event.publication.failed{state}`              | `retrying` は自動の再投入を待つ `FAILED` の出版、`exhausted` はリスナーの方針の上限に達した出版の数である                     |
| `event.publication.failed.oldest.age{state}`   | 各状態で最も古い出版の `publication_date` からの経過秒数である                                                                |

- Gauge は、ロックを取ったかどうかによらず、すべてのインスタンスが毎回同じ値を集計して出す。
  ダッシュボードと警報はインスタンスの間の最大値（`max`）で集め、合計（`sum`）しない。
  合計すると、同じ出版をインスタンスの数だけ数える。
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

`failed.oldest.age{state=retrying}` の警報の閾値は、出版が上限に達して `retrying` から外れるまでの最長の時間より長くする。
上限を N、`wait-age` を W、倍率を m、待ち時間の上限を M、間隔を I とすると、その時間は次のとおりである（リスナーの処理時間は省く）。

```text
Σ_{n=1}^{N} min(W × m^(n-1), M) + N × I
```

本番の値（W = 5 分、I = 1 分、`default` の N = 5、m = 2、M = 1 時間）では、待ち時間が 5、10、20、40、60 分で計 135 分、間隔の遅れが 5 分で、およそ 140 分になる。
閾値は余裕を見て 2 時間 30 分にし、方針の中で最も長いものに合わせる。
警報が出たら、WARN の `unknown type` と次の SQL で古い型の出版を特定する。

```sql
SELECT event_type, listener_id, count(*) AS publications, min(publication_date) AS oldest_published_at
  FROM modulith.event_publication
 WHERE status = 'FAILED'
 GROUP BY event_type, listener_id
 ORDER BY oldest_published_at;
```

今のコードにない `event_type` の出版は、新しい型へ移す要否を決め、移さないものは「再投入しない出版の片付け」の手順で消す。
