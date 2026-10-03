---
type: ADR
title: 'ADR-047: カラム名と型の矛盾を機械検査する'
description: 命名規約が意味を定める接尾辞と接頭辞（_at、_date、is_、has_）を持つカラムについて、名前と型の矛盾を機械検査し、それ以外の型の選択はレビューに残す決定。
tags: [adr, database, datetime, naming]
---

# ADR-047: カラム名と型の矛盾を機械検査する

## Status

Proposed

## Date

2026-10-03

## Context

[日時とタイムゾーンの規約](../datetime/timezone-conventions.md) の「自動強制」は、型の使い分けを機械判定しないとしている。
その値が絶対時刻かどうかという意味の判断を伴うためである。

一方、[PostgreSQL の命名規約](../database/postgresql-naming.md) の「カラム名」は、接尾辞と接頭辞の意味を決めている。
`_at` は絶対時刻、`_date` は日付、`is_` と `has_` はフラグを表す。
そのため、`timestamp` 型の `*_at` のカラムは、命名規約と矛盾する。
この矛盾は、値の意味を判断しなくても、カラム名と型だけから判定できる。

絶対時刻をタイムゾーンなしで保存する誤りは、changeset の型を一語変えるだけで起き、レビューで見落としやすい。

## Decision

カラム名と型の次の組を機械検査する。

- `_at` で終わるカラムは `timestamptz` にする。
- `_date` で終わるカラムは `date` にする。
- `is_` か `has_` で始まるカラムは `boolean` にする。

これらの接尾辞と接頭辞を持たないカラムの型の選択は、レビューで確認する。
Java の `Instant` と、`LocalDateTime` および `ZoneId` の選択も、レビューで確認する。

違反はビルドを失敗させる。
例外は、理由を添えて許可リストに載せる。

日時とタイムゾーンの規約は、この決定と同じ変更で更新する。

## Consequences

### Positive

- 絶対時刻をタイムゾーンなしで保存する誤りを、`_at` の名前を付けたカラムについて機械的に検出できる。
- 命名規約の接尾辞と接頭辞の意味が、名前だけの約束でなくなる。

### Negative

- 地域の日時を正当に持つカラムには、`_at` の接尾辞を使えない。
- 許可リストの保守が増える。

### Neutral

- 接尾辞と接頭辞を持たないカラムは検査しない。

## Alternatives Considered

### 選択肢1: すべてをレビューに任せる

- **Description**：日時とタイムゾーンの規約のとおり、型の選択を機械判定しない。
- **Pros**：検査の実装と許可リストの保守が要らない。
- **Cons**：名前だけで判定できる矛盾も、レビューの見落としで残る。

### 選択肢2: 値の意味から型を機械判定する

- **Description**：カラムが絶対時刻を持つかどうかを判定し、型を検査する。
- **Pros**：接尾辞を持たないカラムも検査できる。
- **Cons**：値の意味はスキーマから分からないため、判定できない。

### 選択肢3: Java の型だけを検査する

- **Description**：jOOQ の生成コードや手書きのコードで、日時の Java の型を検査する。
- **Pros**：Java のコードだけで検査が済む。
- **Cons**：DB のカラムの型の誤りを検出できない。

## References

- [ADR-006: 絶対時刻を UTC / Instant / timestamptz に統一する](ADR-006-utc-instant-absolute-time-policy.md)
- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [日時とタイムゾーンの規約](../datetime/timezone-conventions.md)
- [PostgreSQL の命名規約](../database/postgresql-naming.md)
- [PostgreSQL のデータ型](../database/postgresql-data-types.md)
