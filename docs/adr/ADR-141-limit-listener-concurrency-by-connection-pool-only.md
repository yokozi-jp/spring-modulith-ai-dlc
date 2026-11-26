---
type: ADR
title: 'ADR-141: リスナーの同時実行数に上限を置かず、DB の接続の pool を唯一の制限にし、再投入の 1 回の件数を pool の半分以下に絞る'
description: 非同期リスナーの executor の同時実行数の上限（concurrency-limit）を外して、リスナーを渡す要求のスレッドが空きを待つ問題と、リスナー同士が枠を取り合う問題を同時に消し、DB の接続の pool を唯一の制限にして、再投入の 1 回の件数（max-per-run）を最小の pool の半分に絞る決定。
tags: [adr, integration, async, spring-modulith, capacity, postgresql]
---

# ADR-141: リスナーの同時実行数に上限を置かず、DB の接続の pool を唯一の制限にし、再投入の 1 回の件数を pool の半分以下に絞る

## Status

Proposed

## Date

2026-11-26

## Context

非同期リスナーは、Spring Boot の `applicationTaskExecutor`（仮想スレッドの `SimpleAsyncTaskExecutor`）で動く。
これまでは `spring.task.execution.simple.concurrency-limit: 2` で、同時に動くリスナーを 2 つに絞っていた。
2 は、テストの接続の pool（4）の半分から決めた仮の値で、同期リクエストの接続を残すためだった（[async-flow-control](../integration/async-flow-control.md)）。

この上限が、2 つの問題を生んでいた。

- 上限に達すると、`SimpleAsyncTaskExecutor` は枠が空くまで `execute` を待たせる。
  リスナーを渡す呼び出しは、コミットの後に、出版した要求のスレッドが行う。
  待つ間、その要求は接続を 1 つ持ったままである（[#686](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/686)）。
- 外部システムを呼ぶ遅いリスナーが枠を埋めると、DB だけのリスナーも動き始められない。
  遅いリスナーを 2 つ動かした実測で、DB だけのリスナーは 1494 ms 後に動き始めた（[#685](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/685)、[#557](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/557)）。

専用の executor と自作の注釈で解く案（#685）と、キュー付きの executor で解く案（#686）があった。
どちらも Spring の標準から離れる。
専用の executor の案は、テナントの束縛、ArchUnit の規則、トランザクションの管理、再投入の方針の 4 か所を、新しい注釈に合わせて直す必要がある。
キュー付きの executor の案は、`async-flow-control.md` の「後段のキューを増やさない」方針に例外を作る。
今の main のリスナーは `OrderConfirmedListener` の 1 つで、取り合う相手がまだいない。

上限を外すと、枠がなくなり、待つ場所もなくなる。
2 つの問題が、設定の削除だけで同時に消える。

ただし、上限は同期リクエストの接続を残す役割も持っていた。
接続の pool は、リスナーと同期リクエストの両方が使う。
取得の待ちは `DB_POOL_CONNECTION_TIMEOUT_MS`（5 秒）で、超えると失敗する。
決済のリスナーは、決済代行を待つ間（最悪で約 3 秒）接続を持つ。

再投入のジョブは、1 回の実行で `max-per-run`（100 件）を、一度にリスナーへ渡す。
上限がないと 100 件が同時に接続を取りに行く。
pool が 10 のとき、次の試算になる（最悪の 1 件を 3 秒とする）。

- 0 秒から：10 件が接続を得る。
- 3 秒から：さらに 10 件が接続を得る。
- 5 秒：待ちの上限を超え、残りの約 80 件が接続の取得に失敗する。

失敗した出版は `FAILED` に戻り、再投入の回数（`abandon-after-attempts: 5`）を 1 回消費する。
同時に、同期リクエストの接続の取得も同じ pool で待たされる。

## Decision

リスナーの executor に同時実行数の上限を置かず、DB の接続の pool を唯一の制限にする。

- `spring.task.execution.simple.concurrency-limit` を `application.yaml` から外す。
  `DemoApplicationTest` が、executor の上限がないことを確かめる。
- 再投入のジョブの 1 回の件数（`event-publication.resubmission.max-per-run`）を、100 から 2 に下げる。
  最小の pool（テストの `DB_POOL_MAXIMUM_SIZE=4`）の半分である。
  再投入した出版は同時に全件が接続を取りに行くので、同時に動く数を件数で絞る。
  `DemoApplicationTest` が、`max-per-run` が 1 以上で、pool の半分以下であることを確かめる。
- 接続を取れなかったリスナーは、`connection-timeout` で失敗する。
  出版は `FAILED` に戻り、再投入が拾う。
  リスナーは冪等にする規約（[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)、[冪等性](../integration/async-ordering-and-idempotency.md)）が、この前提を支える。
- 専用の executor（#685）と、キュー付きの executor（#686）は作らない。
- 本番の pool の大きさを負荷試験で決めたら、`max-per-run` を見直す。
- 再投入のジョブを Web と別のインスタンスで動かせば、`max-per-run` の制限は外せる。この検討は [#792](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/792) で行う。

## Consequences

### Positive

- リスナーを渡す要求のスレッドが、executor の空きを待たない。待つ間に余分な接続を持つことがなくなる。
- 外部システムを呼ぶ遅いリスナーが、executor の枠を占めて、他のリスナーの開始を遅らせることがない。
- 専用の executor、自作の注釈、4 か所の修正、キュー付きの executor のどれも要らない。Spring の標準の構成から離れない。
- 設定の数が減る。`concurrency-limit` と、それを pool から導く式が要らなくなる。

### Negative

- 同期リクエストの接続を残す仕組みが、再投入の `max-per-run` だけになる。
  注文が集中して最初の配信が一度に接続を取りに行くと、同期リクエストの接続の取得も待たされ、5 秒を超えると失敗する。
- 最初の配信の集中では、接続を取れなかったリスナーが `FAILED` になり、再投入の回数を 1 回消費する。
  集中が続くと、`ABANDONED` に落ちる出版が出うる。
- 遅いリスナーが接続を占めると、DB だけのリスナーも接続の取得で待たされる。
  executor の枠で防いでいた問題が、pool の中に移る。
- `max-per-run` が 2 なので、再投入の流量は `interval` あたり 2 件になる。
  障害が長く続いた後の回復が遅くなる。手での再投入（`resubmit-once`）も 1 回で 2 件までで、`remaining` が 0 になるまで繰り返す。
- ADR-079 の `lock-at-most-for` 等の時間の関係は、リスナーを渡す待ちがなくなった分、余裕が増える。値は変えない。

### Neutral

- 本番の pool と `max-per-run` を負荷試験で決める。
- 外部システムを呼ぶリスナーが増えて問題が出たら、専用の executor（#685）を再び検討する。
  その場合の設計は、#557 の調査の記録にある。

## Alternatives Considered

### 選択肢1: 上限を残し、pool から導いた値にする

- **Description**：`concurrency-limit` を残し、環境変数で pool の半分から決める。
- **Pros**：同期リクエストの接続を、リスナーから守れる。標準の仕組みだけで済む。
- **Cons**：上限に達すると、リスナーを渡す要求のスレッドが空きを待つ問題が残る（#686）。リスナー同士が枠を取り合う問題も残る（#685）。解くには、#685 と #686 の作業が要る。

### 選択肢2: 外部システムを呼ぶリスナーに専用の executor を持たせる（#685）

- **Description**：`@ApplicationModuleListener` を meta 注釈にして、executor の名前を渡す。
- **Pros**：リスナー同士の枠の取り合いが解ける。
- **Cons**：テナントの束縛などの 4 か所を直す。`spring.task.execution.mode=force` が要る。今は取り合う相手のリスナーがない。出版した要求のスレッドの待ちは解けない。

### 選択肢3: 上限つきのキューを持つ executor にする（#686）

- **Description**：`ThreadPoolTaskExecutor`（`core=2`、`max=2`、`queue-capacity=50`）にする。
- **Pros**：出版した要求のスレッドが待たない。
- **Cons**：`async-flow-control.md` の「後段のキューを増やさない」方針に例外を作る。キューの待ちが `wait-age` を超えると、最初の配信と再投入が重なる。

### 選択肢4: 決済代行の呼び出しを DB のトランザクションの外に出す（#581）

- **Description**：「請求中」の状態を持ち、トランザクションを分ける。
- **Pros**：待つ間の接続を持たない。
- **Cons**：#581 で、今の形を残すと決めた。上限の原因は接続ではなく executor の枠であり、外に出しても解けない。

### 選択肢5: 上限を外し、`max-per-run` を 100 のままにする

- **Description**：上限だけを外す。
- **Pros**：変更が最小になる。
- **Cons**：再投入の 100 件が同時に接続を取りに行き、pool が 10 の試算で約 80 件が失敗する。再投入の回数を消費して `ABANDONED` が増えるため採らない。

## References

- [#791](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/791)：この ADR の作業
- [#685](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/685)、[#686](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/686)、[#557](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/557)、[#581](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/581)、[#792](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/792)
- [非同期処理の流量制御と性能](../integration/async-flow-control.md)
- [ADR-019](ADR-019-define-resilience-and-capacity-guardrails.md)
- [ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)
