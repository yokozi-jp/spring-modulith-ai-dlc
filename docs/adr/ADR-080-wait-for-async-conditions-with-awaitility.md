---
type: ADR
title: 'ADR-080: バックエンドのテストの非同期の条件の待ちに Awaitility を使う'
description: Scenario で待てない別のスレッドの状態を、固定時間の Thread.sleep でなく Awaitility の期限付きの条件の待ちで待ち、Awaitility をテストの依存に明示する決定。
tags: [adr, testing, backend]
---

# ADR-080: バックエンドのテストの非同期の条件の待ちに Awaitility を使う

## Status

Proposed

## Date

2026-10-12

## Context

バックエンドのテストの規約（[バックエンドのテストコードの書き方](../backend/testing-code-style.md)の「非同期待機」）は、固定時間の `Thread.sleep()` を禁じている。
規約が認める待ち方は、イベントの発行と購読を待つ Spring Modulith の `Scenario`、対象の API の期限付きの条件の待ち、JUnit の `assertTimeout` の 3 つだった。

失敗したイベント出版の再投入（[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）のテストは、この 3 つでは待てない状態を待つ。
たとえば、手での 1 回の再投入を別のスレッドで呼び、リスナーが門で止まったことや、ロックを持つ間に再投入が戻らないことを確かめてから門やロックを開ける。
これはイベントの完了ではないため `Scenario` では待てず、対象の API も期限付きの条件の待ちを持たない。
`assertTimeout` は処理の時間の上限を測るもので、条件が成り立つまで繰り返し確かめる仕組みを持たない。
このため最初の実装は、決めた時間だけ眠ってから門を開ける `Thread.sleep` を使い、規約に反した。

Awaitility は、`spring-modulith-starter-test` の推移的な依存としてすでにテストのクラスパスにあり、Spring Boot が版を管理する。
`Scenario` も内部で Awaitility を使い、テストの拡張（`CleanGeneratedTablesExtension`）も処理中の出版の待ちに使っている。

## Decision

Spring Modulith の `Scenario` で待てない非同期の条件は、Awaitility の期限付きの条件の待ちで待つ。

- `org.awaitility:awaitility` を `testImplementation` に明示し、版は Spring Boot の管理に任せる。
- `await("<対象と期待>").atMost(<期限>)` で、別名に対象の ID と期待する条件を書き、期限を必ず付ける。
- 状態の確かめは `untilAsserted` と AssertJ の `as` で書き、期限を過ぎたときのメッセージに最後に観測した状態を出す。
- 「変わらないままである」ことは `during` で確かめ、固定時間の `Thread.sleep` で代えない。
- イベントの発行と購読は、これまでどおり `Scenario` で待つ。
- 処理の時間の上限を測るときは、これまでどおり JUnit の非プリエンプティブな `assertTimeout` を使ってよい。
- `assertTimeoutPreemptively` は、これまでどおり使わない。

## Consequences

### Positive

- 条件が成り立った時点で待ちを終えるため、遅い CI で偶然に失敗せず、速い環境で余計に待たない。
- 期限を過ぎたときに、対象の ID、期待、最後の状態がメッセージに出る。
- 推移的な依存に頼らないため、`spring-modulith-starter-test` の依存が変わっても使い続けられる。

### Negative

- 待ち方が `Scenario` と Awaitility の 2 つになり、どちらを使うかを選ぶ必要がある。
- `during` は決めた時間だけ条件を確かめ続けるため、その時間だけテストが長くなる。

### Neutral

- 対象の API が期限付きの条件の待ちを持つときは、Awaitility でなくその API を使ってよい。

## Alternatives Considered

### 選択肢1: 固定時間の Thread.sleep を残す

- **Description**：決めた時間だけ眠ってから門やロックを開ける。
- **Pros**：依存も書き方も増えない。
- **Cons**：時間が短いと遅い環境で偶然に失敗し、長いと毎回その時間だけ待つ。
  条件が成り立ったかを確かめないため、失敗したときに原因が分からない。

### 選択肢2: JUnit の assertTimeout を使う

- **Description**：処理の時間の上限を JUnit の非プリエンプティブな `assertTimeout` で測る。
- **Pros**：JUnit だけで書ける。
- **Cons**：条件を繰り返し確かめる仕組みを持たず、条件の待ちは手で書く繰り返しになる。

### 選択肢3: 繰り返しの待ちを手で書く

- **Description**：条件を確かめて短く眠ることを、期限まで繰り返す補助関数をテストに書く。
- **Pros**：依存を明示しなくてよい。
- **Cons**：Awaitility と同じものを手で書き直すことになり、期限を過ぎたときのメッセージと割り込みの扱いをテストごとに揃える手間が残る。

## References

- [バックエンドのテストコードの書き方](../backend/testing-code-style.md)
- [ADR-079: 失敗したイベント出版を、ShedLock で 1 つのインスタンスに絞った定期のジョブで、リスナーごとの方針で再投入する](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)
- [Awaitility Usage](https://github.com/awaitility/awaitility/wiki/Usage)
