---
type: ADR
title: 'ADR-081: 一覧 API の sort を domain.model の enum で受け、default のない switch 式で項目名と列に写す'
description: 一覧 API の sort の項目名を、QueryService の default のない switch 式で domain.model の enum に直し、jOOQ の Repository の default のない switch 式で生成済みの列に写す決定。String のキーの Map と、QueryService で SortField を作る案を退けた理由を記す。
tags: [adr, backend, database, jooq, web-api]
---

# ADR-081: 一覧 API の sort を domain.model の enum で受け、default のない switch 式で項目名と列に写す

## Status

Proposed

## Date

2026-10-14

## Context

一覧の API は、`sort` で応答 JSON の項目名と方向（`placedAt:desc`）を受ける（[クエリパラメータ](../web-api/query-parameters.md)）。
その項目名を DB の列へ写す方法を、規約が定めていなかった（issue #171）。

項目名から列を作るときに `DSL.name(..)` などへリクエストの値を渡すと、識別子は引用符で囲まれるが、生成済みのメタモデルにない列やほかのテーブルの列を参照できる。
issue #171 は、`Map<String, Field<?>>` の許可リストで生成済みの列へ写す案を挙げていた。

制約は次のとおりである。

- QueryService は jOOQ の API と生成型に依存しない（[クラスの役割：QueryService](../backend/class-roles/query-service.md)）。
- Repository は `domain.model` の型を受け取り、Presentation の語彙（応答 JSON の項目名）を受け取らない（[クラスの役割：Repository](../backend/class-roles/repository.md)）。
- 並べられる項目を一つ足すときに、写し忘れをできるだけコンパイルで見つけたい。

## Decision

- Repository は並び順を `domain.model` の enum（`OrderSortKey`）と record（`OrderSort`）のリストで受け取る。
- QueryService は検索条件の `sort` の要素を `OrderSort` に直す。
  項目名と enum の対応は、enum から項目名を返す `default` のない `switch` 式一つに書く。
  対応のない項目名は、Controller の検査とのずれを表すプログラムの誤りとして `IllegalStateException` にする。
- jOOQ の Repository は、enum から生成済みの列を返す `default` のない `switch` 式で列を選び、最後に主キーを足す。
- 許可値にない `sort` は、Controller の Bean Validation が 400 で拒否する。
- `DSL.name`、`DSL.quotedName`、`DSL.systemName`、`Name.append` を手書きのコードで使わないことを ArchUnit で検査する。

## Consequences

### Positive

- enum に定数を足して、QueryService の項目名か Repository の列を書き忘れると、コンパイルが失敗する。
- 応答 JSON の項目名は QueryService より内側に入らず、jOOQ の型は Repository の外に出ない。
- 列は生成済みのメタモデルから選ばれ、リクエストの値が識別子にならない。

### Negative

- 項目を一つ足すときに、OpenAPI の `enum`、Controller の Bean Validation、`OrderSortKey`、QueryService の `switch` 式、Repository の `switch` 式を直す。
  Controller の検査に足し忘れても、コンパイルは失敗せず、その項目が 400 になる。
- Controller で OpenAPI に `enum`、`style: form`、`explode: false` を出す書き方は決まっていない。
  `@Pattern` で検査すると、OpenAPI には `pattern` が出る。
- ArchUnit の規則は文字列から `Name` を作る主な入口を塞ぐだけであり、`Table.field(String)` と `DSL.field(Name)` へリクエストの値を渡すことは検査しない。

### Neutral

- Controller の書き方は、並べ替えできる最初の一覧 API を作るときに決め、[クラスの役割：Controller](../backend/class-roles/controller.md) に足す。

## Alternatives Considered

### 選択肢1: String のキーの Map で列を引く

- **Description**：Repository が応答 JSON の項目名を受け取り、`Map<String, Field<?>>` で生成済みの列を引く（issue #171 の案）。
- **Pros**：変換の段が一つ減る。
- **Cons**：許可値と `Map` のキーがずれてもコンパイルで見つからず、足し忘れは実行時の `null` になる。応答 JSON の項目名が Infrastructure に入る。

### 選択肢2: QueryService で jOOQ の SortField を作る

- **Description**：QueryService が項目名から `SortField` を作り、Repository に渡す。
- **Pros**：enum を作らずに済む。
- **Cons**：QueryService が jOOQ の API と生成型に依存し、[クラスの役割：QueryService](../backend/class-roles/query-service.md) の依存の規則に反する。

### 選択肢3: Domain の enum に応答 JSON の項目名を持たせる

- **Description**：`OrderSortKey` の各定数のコンストラクタの引数に `placedAt` を持たせ、QueryService はそれで引く。
- **Pros**：項目名の書き忘れもコンパイルで見つかり、対応が enum の定義と並ぶ。
- **Cons**：Presentation の語彙が Domain に入る。

## References

- issue #171
- [ADR-003](ADR-003-adopt-jooq-for-data-access.md)
- [ADR-013](ADR-013-standardize-http-api-contracts.md)
- [jOOQのSQLの書き方](../database/jooq-usage.md)
