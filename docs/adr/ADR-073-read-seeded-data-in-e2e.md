---
type: ADR
title: 'ADR-073: E2E はシーダーの代表データを読むだけにし、変更するデータは各テストが作る'
description: task e2e が migration の後にシーダーで代表データを一度入れ、spec はその商品と代表の注文を読むだけにし、変更する注文は各テストが画面か公開 API で一意に作る決定。
tags: [adr, e2e, testing, seed]
---

# ADR-073: E2E はシーダーの代表データを読むだけにし、変更するデータは各テストが作る

## Status

Proposed

## Date

2026-10-08

## Context

[ADR-057](ADR-057-adopt-playwright-for-e2e-tests.md) は、E2E のデータを各テストが公開 API で作り、Datafaker のシーダーと共有しないとした。
[#167](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/167) は、注文を作って確定し、決済の結果を画面で確かめる E2E を求めている。
しかし商品には作成の公開 API が無く、空の DB では注文を作れない。
同じ #167 は、シーダーの代表の状態（下書き、確定済み、取消済み、決済の拒否、再投入を待つ注文）を主要な画面と失敗のシナリオで使うことも求めている。

ADR-057 がシーダーとの共有を避けた理由は、データが実行環境に依存して結果が変わることと、テストが互いのデータを変えて実行順に依存することである。
シーダーは乱数の seed と時刻を固定しており、同じ DB の状態から同じ行を入れる（#114）。
`task e2e` は毎回、空の DB から始まる。

## Decision

`task e2e` は、`task be-migrate` の後に `task be-seed` で代表データを一度入れる。
spec は、シーダーの商品と代表の注文を読むだけにし、変更しない。
spec が変更する注文は、そのテストが画面か公開 API で作り、客先注文番号に一意な値を含める。

- シーダーは今までどおりテストのソースに置き、bootJar と `runtimeClasspath` に入れない。
- 商品名は Datafaker の値なので、spec は名前を固定で書かず、`GET /api/products` の結果か選択肢の位置で選ぶ。
- 代表の注文は客先注文番号（`SEED-C01` から `SEED-C06`）で探す。
- 公開 API で作れず、シーダーにも無い状態が必要になったら、DB fixture を使う前に別の ADR で判断する（ADR-057 の方針のまま）。

## Consequences

### Positive

- 商品の作成の API を E2E のためだけに足さずに、注文の流れを E2E で確かめられる。
- 主要な画面が、シーダーの代表の状態を表示できることを E2E で確かめられる。
- 変更するデータは各テストが作るため、並列の実行と実行順への非依存（ADR-057）を保てる。

### Negative

- E2E がシーダーの代表の状態（客先注文番号と状態の組）に依存し、シーダーを変えると spec も直す必要がある。
- `task e2e` が、テストのソースのコンパイルとシーダーの実行の分だけ長くなる。
- spec が読むだけの決まりは規約とレビューで守り、検査の仕組みは無い。

### Neutral

- シーダーは E2E のために、決済の拒否と、決済記録の無い確定済みの注文の 2 つの状態を足す。
- 注文の一覧は全件を表示するため、各テストが作った注文と代表の注文が同じ一覧に並ぶ。
  spec は自分の客先注文番号か代表の番号で行を探す。

## Alternatives Considered

### 選択肢1: E2E のためだけに商品の作成の API を足す

- **Description**：商品を作る公開 API を足し、各テストが商品から作る。
- **Pros**：ADR-057 の方針をそのまま保てる。
- **Cons**：業務の要件に無い API と認可の判断が増え、本番のコードに E2E のための入口が残る。
  シーダーの代表の状態を画面で使うという #167 の要求も満たさない。

### 選択肢2: SQL の DB fixture で商品と注文を入れる

- **Description**：E2E の開始時に SQL で行を入れる。
- **Pros**：E2E に必要な行だけを入れられる。
- **Cons**：シーダーと別に同じ代表の状態を保つ必要があり、スキーマを変えるたびに 2 か所を直す。

### 選択肢3: 今のまま、E2E でデータを入れない

- **Description**：シーダーを E2E で使わない。
- **Pros**：E2E がシーダーに依存しない。
- **Cons**：商品が無いため、注文を作れず、#167 の主要な流れを E2E で確かめられない。

## References

- [#167 参照業務機能の主要フローと障害回復を検証する](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/167)
- [#114 開発用 Datafaker シーダーを最初の永続化機能とともに導入する](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/114)
- [ADR-057: E2E テストに Playwright と Chromium を採用し、テストデータを公開 API で作る](ADR-057-adopt-playwright-for-e2e-tests.md)
- [E2E テストの方針と書き方](../e2e/testing-strategy.md)
