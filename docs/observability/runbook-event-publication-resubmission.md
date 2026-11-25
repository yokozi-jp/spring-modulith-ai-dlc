---
type: Runbook
title: イベント出版の再投入のログイベント
description: 失敗したイベント出版の定期の再投入（EventPublicationResubmitter）、回数の上限での諦め（Spring Modulith の abandon-after-attempts）、手での 1 回の再投入（EventPublicationResubmitOnce）が出す ERROR と WARN の運用定義と、上限に達した出版の調べ方、戻し方、手での再投入、片付け、自動の再投入の停止、メトリクスと警報の見方を定める。そのログやメトリクスに対応するとき、再投入の設定を変えるときに使う。
tags: [runbook, observability, logging, operations, integration, spring-modulith]
---

# イベント出版の再投入のログイベント

定期のジョブは、リスナーの失敗で `FAILED` に残ったイベント出版を、リスナーの方針の待ち時間の後に、回数の上限まで再投入する（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
上限の回数目の再投入でまた失敗した出版は、Spring Modulith が `ABANDONED` にして archive の表（`modulith.event_publication_archive`）へ移し、定期のジョブが出版ごとの `ERROR` で知らせる。
方針の `abandon-after-attempts` が 1 のリスナーの出版は、最初の配信の失敗で `ABANDONED` になり、`ResubmissionPolicies` が出版ごとの `ERROR` で知らせる。
その後は運用者がこの文書の手順で扱う。
仕組みの全体像は[イベント出版の再投入の全体像](../integration/event-publication-resubmission.md)を参照する。
運用定義の項目は[運用者が対応するログイベントの管理](log-operational-events.md)に従う。
`Event publications resubmitted` と `Event publications resubmitted once`（`INFO`）、`Event publication resubmission skipped`（`DEBUG`）は運用定義を要求しないため書かない。

## リスナーの方針

方針は `application.yaml` の `event-publication.resubmission.policies.<名前>` にあり、`multiplier`（倍率）と `max-wait`（待ち時間の上限）を持つ。
リスナーの `on` に付けた `@ResubmissionPolicy("<名前>")` で選び、付けないリスナーは `default` になる。
main では `payment.application.OrderConfirmedListener` が `payment` を使い、`default` と `payment` はどちらも 2 倍、1 時間である。
回数の上限の既定は全リスナーに共通で、`spring.modulith.events.resubmission.abandon-after-attempts`（main では 5）である。
2 未満にすると起動に失敗する。

方針に `abandon-after-attempts` を書くと、その方針のリスナーだけ、その回数で諦める。
数え方は全体の値と同じで、2 以上の N は N 回目の再投入の失敗で諦める。
`1` は最初の配信の失敗で `ABANDONED` にして、自動では再投入しない。
冪等キーを持たない外部システムを呼ぶリスナーに使う。
全体の値以上にすると起動に失敗する。
諦めた出版は、`ABANDONED` の出版と同じ手順（「上限に達した出版の戻し方」）で運用者が扱う。

```yaml
policies:
  no-resubmit:
    multiplier: 2
    max-wait: PT1H
    abandon-after-attempts: 1
```

`1` のリスナーの出版は、配信の途中で落ちて古い出版として `FAILED` に戻っても、定期のジョブが再投入せず、`over-limit` に数える（「Failed event publications exceed the resubmission limit」）。
外部の呼び出しの後で落ちた出版は、外部の処理だけが済んでいることがある。
手で再投入する前に、外部システムでその出版の処理が済んでいないかを確かめる。
2 以上の値のリスナーは、配信の途中で落ちた出版を上限まで再投入するため、リスナーの冪等性に頼る。

n 回目の再投入の前に、最後の試行から `min(wait-age × multiplier^(n-1), max-wait)` を待つ。
`wait-age` は環境変数 `EVENT_PUBLICATION_RESUBMISSION_WAIT_AGE` である。
定期のジョブは、ロックを取った回の再投入の前に、落ちたまま残った出版を古い出版として `FAILED` に戻す。
`PUBLISHED` の出版は `wait-age` を過ぎたら戻す。
`PROCESSING` と `RESUBMITTED` の出版を戻すまでの `in-flight-staleness` は環境変数 `EVENT_PUBLICATION_RESUBMISSION_IN_FLIGHT_STALENESS` で、本番は `PT25M` である。
`in-flight-staleness` は最後の出版か再投入の時刻から測り、`lock-at-most-for`（10 分）の 2 倍より長くしないと起動に失敗する。

出版の `listener_id` は、リスナーのメソッドの完全修飾名と引数の型である（例：`com.example.demo.payment.application.OrderConfirmedListener.on(com.example.demo.ordering.OrderConfirmed)`）。

## Event publication resubmission exhausted

- **event 名**：`Event publication resubmission exhausted`
- **レベル**：`ERROR`
- **事象**：定期のジョブが再投入した出版が、上限（`abandon-after-attempts`）の回数目の再投入でまた失敗し、Spring Modulith が `ABANDONED` にして archive の表へ移した。
  再投入したインスタンスが、次の回の先頭で archive の行を読み戻したときに 1 回だけ出す。
  手での 1 回の再投入でまた失敗した出版には出さない（「Event publication resubmit-once incomplete」）。
  `ABANDONED` の出版は、定期の再投入、手での 1 回の再投入、古い出版を `FAILED` に戻す処理のどれの対象にもならない。
- **属性**：`event_publication.id`、`event_publication.event_type`、`event_publication.listener_id`、`event_publication.completion_attempts`（archive の行の値で、ふつうは上限 + 1）
- **発生原因**：外部システムの障害が方針の待ち時間と上限の回数を超えて続いた。
  リスナーの不具合や内部の不変条件の違反（ADR-072 のデッドレター）で、何度動かしても失敗する。
  リスナーの名前を変えたデプロイで、古い出版のリスナーが見つからない。
- **対応**：「上限に達した出版の調べ方」の SQL で出版とリスナーを特定し、リスナーの ERROR のログと trace で原因を確かめる。
  原因を直したら「上限に達した出版の戻し方」で `FAILED` に戻してから「手での再投入」で再投入し、再投入しないと決めた出版は「再投入しない出版の片付け」で消す。
- **通知**：再投入したインスタンスがその前に落ちたときと、その回のロックの解放に失敗したときは出ないため、`failed.oldest.age{state=exhausted}` の警報が補う（「メトリクスの見方」）。
  監視の通知は `event_publication.id` でまとめる。

## Event publication abandoned on first delivery

- **event 名**：`Event publication abandoned on first delivery`
- **レベル**：`ERROR`
- **事象**：方針の `abandon-after-attempts` が 1 のリスナーの最初の配信が失敗し、Spring Modulith が `ABANDONED` にして archive の表へ移した。
  定期のジョブは再投入しない。
  失敗したインスタンスが、諦めると決めたときに 1 回だけ出す。
  `event.publication.resubmission.exhausted` の Counter と「Event publication resubmission exhausted」には数えない。
- **属性**：`event_publication.id`、`event_publication.event_type`、`event_publication.listener_id`、`event_publication.resubmission_policy`（方針の名前）
- **発生原因**：外部システムの障害、リスナーの不具合や内部の不変条件の違反。
  外部の呼び出しがタイムアウトした場合は、外部の処理が済んでいることがある。
- **対応**：リスナーの ERROR のログと trace で原因を確かめ、外部システムでその出版の処理が済んでいるかを確かめる。
  済んでいなければ、原因を直してから「上限に達した出版の戻し方」で `FAILED` に戻し、「手での再投入」で再投入する。
  済んでいれば、「再投入しない出版の片付け」で消す。
- **通知**：ERROR を出す前にインスタンスが落ちると出ないため、`failed.oldest.age{state=exhausted}` の警報が補う（「メトリクスの見方」）。
  監視の通知は `event_publication.id` でまとめる。

## Failed event publications exceed the resubmission limit

- **event 名**：`Failed event publications exceed the resubmission limit`
- **レベル**：`ERROR`
- **事象**：`completion_attempts` が、リスナーの再投入の回数の上限を超えたまま `FAILED` に残った出版がある。
  上限は、リスナーの方針の `abandon-after-attempts`（なければ全体の値）が 2 以上なら同じ値、1 なら 0 である。
  定期のジョブはこの出版を再投入しない。
  各インスタンスが Gauge を集計する回に、その件数（`event.publication.failed{state=over-limit}`）が 0 から増えたときだけ 1 つ出す。
  件数が 0 より大きい間は出さず、0 に戻った後にまた増えたら出す。
  前の件数はインスタンスのメモリにあるため、起動の直後の回に件数が 0 より大きいときも出す。
- **属性**：`event_publication.over_limit.count`（0 から増えた回に数えた、上限を超えた `FAILED` の出版の数）
- **発生原因**：最後の再投入の途中でインスタンスが落ちた。
  処理中の出版を、古い出版として `FAILED` に戻した。
  `ABANDONED` にする `markAbandoned` が DB の失敗で終わり、古い出版として `FAILED` に戻した。
  方針の `abandon-after-attempts` が 1 のリスナーの最初の配信の途中で落ちたか、配信が `wait-age` より長く待った。
  どれも失敗を記録しないため、Spring Modulith は `ABANDONED` にしない。
  「上限に達した出版の戻し方」で戻したまま、手での再投入をしていない出版も数える。
- **対応**：次の SQL で出版を特定し、リスナーの ERROR のログで原因を確かめる。
  原因を直したら「手での再投入」で再投入し、再投入しないと決めた出版は「再投入しない出版の片付け」で消す。
  手での再投入でまた失敗すると `ABANDONED` になる。

  ```sql
  -- :max は spring.modulith.events.resubmission.abandon-after-attempts（main では 5）
  -- 方針の abandon-after-attempts を書いたリスナーは、:listener_id と、その上限（1 なら 0）で別に読む
  SELECT id, event_type, listener_id, publication_date, last_resubmission_date, completion_attempts
    FROM modulith.event_publication
   WHERE status = 'FAILED'
     AND completion_attempts > :max
   ORDER BY publication_date;
  ```

  方針の `abandon-after-attempts` が 1 のリスナーの出版は、外部の処理だけが済んでいることがあるため、外部システムを確かめてから再投入する。

- **通知**：同じ増加を、ロックを取ったかどうかによらず各インスタンスが 1 つずつ出す。
  通知はこの event 名でまとめる。
  扱うまで残っていることは、`failed{state=over-limit}` の警報で知る（「メトリクスの見方」）。

## Event publication gauges refresh failed

- **event 名**：`Event publication gauges refresh failed`
- **レベル**：`WARN`
- **事象**：`event.publication.failed` と `event.publication.failed.oldest.age` の集計が DB の例外で失敗した。
  Gauge は前の値のまま残り、その回の再投入は続ける。
- **属性**：`exception.type`、`exception.message`、`exception.stacktrace`
- **発生原因**：`statement_timeout`、接続の pool の枯渇、DB の停止。
- **対応**：1 回だけなら対応しない。
  続くと Gauge の値と警報が古いままになるため、当日中に DB の状態を確かめる。

## Event publication resubmission failed

- **event 名**：`Event publication resubmission failed`
- **レベル**：`WARN`
- **事象**：定期のジョブの 1 回の実行が DB の例外かロックの例外で失敗し、その回の再投入をしなかった。
  古い出版を `FAILED` に戻す処理が失敗した回も、ここに含む。
- **属性**：`exception.type`、`exception.message`、`exception.stacktrace`
- **発生原因**：接続の pool の枯渇（`connection-timeout`）、DB の停止、`statement_timeout`、`modulith.shedlock` の読み書きの失敗（ShedLock の `LockException`）。
- **対応**：次の回で再試行するため、1 回だけなら対応しない。
  続くときは自動の回復が止まっているので、当日中に DB の状態と接続の pool を確かめる。

## Event publication resubmission crashed

- **event 名**：`Event publication resubmission crashed`
- **レベル**：`ERROR`
- **事象**：定期のジョブの 1 回の実行が、DB の例外とロックの例外以外の予期しない実行時の例外で失敗し、その回の再投入をしなかった。
  この回は `event.publication.resubmission.runs{outcome=failed}` に数える。
- **属性**：`exception.type`、`exception.message`、`exception.stacktrace`
- **発生原因**：コードの不具合（`NullPointerException` など）、方針や設定の不整合、ライブラリの版を上げたあとの挙動の変化。
- **対応**：例外の型とスタックトレースで原因を特定し、当日中に直してデプロイする。
  回ごとに同じ例外が続くため、自動の回復は止まっている。
  直すまでの間は、必要な出版を「手での再投入」で再投入する。
- **通知**：`ERROR` のため通知する。
  同じ原因で回ごとに出るため、通知は例外の型でまとめる。

## Event publication resubmit-once incomplete

- **event 名**：`Event publication resubmit-once incomplete`
- **レベル**：`WARN`
- **事象**：手での 1 回の再投入で、再投入した出版の一部が完了しなかったか、`max-per-run` を超えて再投入しなかった出版が残った。終了コードは 1 である。
- **属性**：`event_publication.resubmitted.count`、`event_publication.completed.count`、`event_publication.incomplete.count`、`event_publication.remaining.count`
  `resubmitted` は、再投入して `RESUBMITTED` へ進んだ出版の数である。選ばれても進まなかった出版は、どの属性にも数えない。
- **発生原因**：原因が直っておらずリスナーがまた失敗した（`incomplete`）。
  リスナーが `completion-timeout`（10 分）を過ぎても終わらない（`incomplete`）。
  対象が `max-per-run`（100 件）より多い（`remaining`）。
- **対応**：`incomplete` が 0 でなければ、リスナーの ERROR で原因を確かめ、直してから起動し直す。
  リスナーの ERROR がなければ、archive の表のその出版の `id` の `status` を確かめる。
  `COMPLETED` なら完了しており、`ABANDONED` なら回数の上限を超えてまた失敗した。
  `FAILED` に残っていれば同じ対象で起動し直す。
  完了した出版と `ABANDONED` の出版は `FAILED` に残らないため、同じ対象で起動し直しても重ねて処理しない。
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
`completion-mode: archive` のため、上限に達した出版は archive の表に `status = 'ABANDONED'` で入る。
`completion_date` は `ABANDONED` にした時刻である。

```sql
-- 上限に達した出版の一覧
SELECT id,
       event_type,
       listener_id,
       publication_date       AS first_attempted_at,
       last_resubmission_date AS last_attempted_at,
       completion_date        AS abandoned_at,
       completion_attempts,
       completion_attempts - 1 AS resubmissions
  FROM modulith.event_publication_archive
 WHERE status = 'ABANDONED'
 ORDER BY publication_date;

-- イベントの型とリスナーごとの件数と最古の時刻
SELECT event_type, listener_id, count(*) AS publications, min(publication_date) AS oldest_first_attempted_at
  FROM modulith.event_publication_archive
 WHERE status = 'ABANDONED'
 GROUP BY event_type, listener_id
 ORDER BY oldest_first_attempted_at;
```

## 上限に達した出版の戻し方

Spring Modulith 2.2.0 には、`ABANDONED` の出版を `FAILED` に戻す API がない（ADR-079）。
そのため、再投入する出版は、次の SQL で archive の表から未完了の表へ戻す。
`completion_attempts` と `last_resubmission_date` は archive の行の値をそのまま写す。
`completion_attempts` はリスナーの再投入の回数の上限を超えているため、定期のジョブは戻した出版を再投入せず、次の節の手での再投入だけが扱う。
方針の `abandon-after-attempts` が 1 のリスナーの出版は、archive の行の値が 1 で、上限は 0 である。
戻した出版は `failed{state=over-limit}` に数え、件数が 0 から増えると ERROR（「Failed event publications exceed the resubmission limit」）も出る。
手での再投入は待ち時間を見ず、`publication_date` が今より前の `FAILED` の出版を読むため、`last_resubmission_date` によらず対象にする。
戻した出版がまた失敗すると、すぐに `ABANDONED` に戻る。
回数の上限をもう一度使わせたいときだけ、`completion_attempts` を 1 にする。
方針の `abandon-after-attempts` が 1 のリスナーの出版は、1 にしても定期のジョブが再投入しない。
1 にした出版は、最後の試行からふつう `wait-age` を過ぎているため、定期のジョブが次の回に再投入する。

行の削除と挿入を 1 つの文にし、途中で止まっても同じ `id` が両方の表に残らないようにする。
両方の表に同じ `id` があると、再投入の結果の読み戻しが失敗し、定期のジョブの回が「Event publication resubmission failed」で終わり続ける。

```sql
WITH moved AS (
  DELETE FROM modulith.event_publication_archive
   WHERE id = :id
     AND status = 'ABANDONED'
  RETURNING id, listener_id, event_type, serialized_event, publication_date,
            completion_attempts, last_resubmission_date
)
INSERT INTO modulith.event_publication
       (id, listener_id, event_type, serialized_event, publication_date,
        status, completion_date, completion_attempts, last_resubmission_date)
SELECT id, listener_id, event_type, serialized_event, publication_date,
       'FAILED', NULL, completion_attempts, last_resubmission_date
  FROM moved;
```

挿入した件数（`INSERT 0 1`）が 1 であることを確かめる。
0 なら、`id` が違うか、すでに戻されたか消された。

## 手での再投入

上限に達した出版は、自動では再投入しない。
原因を直したら、前の節の手順で `FAILED` に戻し、同じイメージを 1 回だけ起動して、環境変数 `EVENT_PUBLICATION_RESUBMIT_ONCE_TARGET` に対象を渡す。
対象は次のいずれかである。

- **`all`**：すべての `FAILED` の出版。
- **`policy:<名前>`**：その方針のリスナーの出版（例：`policy:payment`）。
- **`event-type:<クラス>`**：そのイベントの出版（例：`event-type:com.example.demo.ordering.OrderConfirmed`）。

対象は空白なら通常の起動で、空白以外なら `false` を含めて手での再投入の求めと読む。
`all`、`policy:<名前>`、`event-type:<クラス>` のどれでもない値は、起動して何もしない状態にはならず、終了コード 1 で失敗する。
対象を渡すと、アプリは Web のサーバーと定期の実行を動かさない。
定期のジョブと同じロックを取るため、定期のジョブを止めずに起動してよい。
ロックの中で、定期のジョブと同じく、対象によらず古い出版を `FAILED` に戻す。
定期の実行を止めた環境でも、落ちたまま残った出版はこの起動で `FAILED` に戻り、対象に合えば同じ起動で再投入される。
待ち時間を見ずに、対象の `FAILED` の出版を `max-per-run`（100 件）まで再投入し、リスナーの処理が終わるまで待ってから終わる。
すべて完了したら終了コード 0、それ以外は 1 である。
定期のジョブと違い、時間の予算（次の節）は適用しない。
運用者が完了を待つ入口であり、件数は `max-per-run` が抑える。

ローカルでは Compose の `backend` サービスで起動する。

```bash
docker compose --env-file .env -f docker/compose.yml --profile backend run --rm \
  -e EVENT_PUBLICATION_RESUBMIT_ONCE_TARGET=policy:payment backend
```

本番と STG では、同じタスク定義を ECS の RunTask で 1 回だけ動かすか、Kubernetes の Job にし、環境変数を足す。
起動した後は、`Event publications resubmitted once` の `INFO` の件数と終了コードで結果を確かめる。

## 定期の実行の時間の予算

定期の 1 回の実行は、開始から `lock-at-most-for / 2`（main では 5 分）を過ぎたら、以降の候補を選ばずに次の回へ回す。
再投入の前に動く、古い出版を `FAILED` に戻す処理の時間も、この予算に入る。
古い出版から処理するため、残りは次の回で先に処理される。
`lock-at-most-for` を超えるとロックが切れて他のインスタンスが動くため、その手前で止める。

main の見積もりは次のとおりである。
決済のリスナーの 1 件の最悪は、connect 1 秒 + read 2 秒の約 3 秒で、試行は 1 回である。
`concurrency-limit` は 2 で、100 件（`max-per-run`）ではおよそ 2.5 分になり、`lock-at-most-for` の 10 分に収まる。
リスナーを増やした場合は、予算で止まった回が増えていないか、`failed.oldest.age{state=retrying}` で確かめる。

## 再投入しない出版の片付け

再投入しないと決めた出版（消した注文を指す出版、名前を変えたリスナーの古い出版など）は、`id` を指定して消す。
消す前に、上の一覧の SQL の結果を調査の記録に残す。
上限に達した出版は archive の表から、まだ上限に達していない出版は未完了の表から消す。

```sql
DELETE FROM modulith.event_publication_archive
 WHERE id = :id
   AND status = 'ABANDONED';

DELETE FROM modulith.event_publication
 WHERE id = :id
   AND status = 'FAILED';
```

片付けたあとは、`event.publication.failed{state=exhausted}`、`state=over-limit`、`state=retrying` のどれかが減ったことで確かめる。

## 自動の再投入の停止

環境変数 `EVENT_PUBLICATION_RESUBMISSION_ENABLED` を `false` にしてデプロイする。
値は `true` か `false` だけを使う。
条件は `true`（大文字小文字を区別しない）だけを真と読み、`yes` や `1` は静かに無効の扱いになる。
定期の実行が止まり、落ちたまま `PUBLISHED`、`PROCESSING`、`RESUBMITTED` に残った出版も `FAILED` に戻らなくなる。
止めている間に戻すときは、手での再投入を起動する（「手での再投入」）。
再開するときは `true` に戻してデプロイする。

## メトリクスの見方

名前の `exhausted`（`event.publication.resubmission.exhausted`、`failed{state=exhausted}`、ERROR の「Event publication resubmission exhausted」）は、回数の上限に達して `ABANDONED` になった出版を指す。
上限を超えたまま `FAILED` に残った出版は `over-limit`（`failed{state=over-limit}`、属性の `event_publication.over_limit.count`）と呼び、`exhausted` に含めない。

| 名前                                               | 見方                                                                                                                                                                                                                                   |
| -------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `event.publication.resubmission.runs{outcome}`     | `completed` はロックを取って最後まで動いた回、`skipped` は他のインスタンスがロックを持っていた回、`failed` は WARN の `failed` か ERROR の `crashed` の回である                                                                        |
| `event.publication.resubmissions`                  | 再投入した（実際に `RESUBMITTED` へ進んだ）出版の数。他の更新で進めなかった出版と、`max-per-run` で止まった候補は数えない                                                                                                              |
| `event.publication.resubmission.outcomes{outcome}` | 再投入した出版の結果。`completed` は archive に `COMPLETED` で移った数、`failed` は `FAILED` に戻った数（古い出版として戻した場合を含む）、結果を読む前に再び再投入された数、`ABANDONED` で移った数。1 回の再投入につき 1 回だけ数える |
| `event.publication.resubmission.exhausted`         | 定期のジョブが再投入した出版のうち、回数の上限を超えて失敗し、`ABANDONED` になった数。再投入したインスタンスが次の回に数え、同時に ERROR の `exhausted` を 1 回記録する                                                                |
| `event.publication.failed{state}`                  | `retrying` は自動の再投入を待つ `FAILED` の出版（`completion_attempts` がリスナーの上限以下）、`over-limit` は上限を超えて `FAILED` に残った出版、`exhausted` は archive の表の `ABANDONED` の出版の数である                           |
| `event.publication.failed.oldest.age{state}`       | 各状態で最も古い出版の `publication_date` からの経過秒数である                                                                                                                                                                         |

- Counter は、再投入したインスタンスだけが数える。
  インスタンスの間で二重に数えないため、ダッシュボードは合計（`sum`）で集める。
  Gauge とは逆である。
- ロックの解放に失敗した回の再投入は、`event.publication.resubmissions` に数えず、次の回に `event.publication.resubmission.outcomes` にだけ数えるため、2 つの Counter の値は一致しないことがある。
- `outcomes` は再投入した次の回の先頭で数えるため、結果の判定は最大で間隔と処理時間だけ遅れる。
  `completed` と `ABANDONED` の見分けは `completion-mode: archive` が前提で、`update` の行は数えない。
  処理中の出版は次の回に回し、運用者が消した出版は数えずに忘れる。
  未完了のまま `max(3 × lock-at-most-for, in-flight-staleness + lock-at-most-for + 2 × interval)` を過ぎた出版も数えずに捨てる。
- 再投入した直後にそのインスタンスが落ちると、その回の結果は数えない。
  `failed` と `exhausted` の Gauge と、最古の経過時間の警報が補う。
- Gauge は、ロックを取ったかどうかによらず、すべてのインスタンスが毎回同じ値を集計して出す。
  ダッシュボードと警報はインスタンスの間の最大値（`max`）で集め、合計（`sum`）しない。
  合計すると、同じ出版をインスタンスの数だけ数える。
- 定期の実行を無効にした環境では更新されず、0 のままである。
- `failed{state=retrying}` は、`concurrency-limit` の空きを待つ間に古い出版として `FAILED` に戻した処理中の出版も数えうるため、実際の失敗より多く出ることがある。
- `failed{state=exhausted}` の件数は、残っている件数の表示と片付けたあとの確認に使い、警報にしない。
  archive の表の `ABANDONED` の行は、戻すか消すまで残り、数え続ける。
- 上限に達した出版を知る主な通知は ERROR のログの監視である。
- `failed.oldest.age{state=exhausted}` が 1 日を超えたら通知する。
  ERROR は出版ごとに 1 回しか出ないため、見落とした出版が残り続けるのをこの警報で拾う。
  通知を受けたら「Event publication resubmission exhausted」の節の手順で片付ける。
- `failed{state=over-limit}` が 0 より大きい間は通知する。
  この出版は自動では再投入も `ABANDONED` にもならず、運用者が扱うまで残るため、件数で警報にする。
  ERROR（「Failed event publications exceed the resubmission limit」）は 0 から増えたときだけ出るため、残り続けることはこの警報で知る。
  `failed.oldest.age{state=over-limit}` は、どれだけ前の出版が残っているかの確認に使う。
  `retrying` と `over-limit` は `FAILED` の出版を `completion_attempts` で分けるため、同じ出版を両方に数えない。
- `failed.oldest.age{state=retrying}` にも警報を置く（次の節）。

## イベントの型の名前を変えた出版

イベントのクラスの名前やパッケージを変えたデプロイの後、古い出版は Spring Modulith が読み出しで落とし、毎回 WARN `Event '...' of unknown type '...' found` を記録する。
`completion_attempts` が増えないため上限に達せず、`ABANDONED` にならず、ERROR も出ない。
その出版は `failed{state=retrying}` に残り、`failed.oldest.age{state=retrying}` が伸び続ける。

`failed.oldest.age{state=retrying}` の警報の閾値は、出版が上限に達して `ABANDONED` になり、`retrying` から外れるまでの最長の時間より長くする。
上限（`abandon-after-attempts`）を N、`wait-age` を W、倍率を m、待ち時間の上限を M、間隔を I とすると、その時間は次のとおりである（リスナーの処理時間は省く）。

```text
Σ_{n=1}^{N} min(W × m^(n-1), M) + N × I
```

本番の値（W = 5 分、I = 1 分、N = 5、`default` の m = 2、M = 1 時間）では、待ち時間が 5、10、20、40、60 分で計 135 分、間隔の遅れが 5 分で、およそ 140 分になる。
閾値は余裕を見て 2 時間 30 分にし、方針の中で最も長いものに合わせる。
警報が出たら、WARN の `unknown type` と次の SQL で古い型の出版を特定する。

```sql
SELECT event_type, listener_id, count(*) AS publications, min(publication_date) AS oldest_published_at,
       max(completion_attempts) AS max_completion_attempts
  FROM modulith.event_publication
 WHERE status = 'FAILED'
 GROUP BY event_type, listener_id
 ORDER BY oldest_published_at;
```

今のコードにない `event_type` の出版は、新しい型へ移す要否を決め、移さないものは「再投入しない出版の片付け」の手順で消す。
