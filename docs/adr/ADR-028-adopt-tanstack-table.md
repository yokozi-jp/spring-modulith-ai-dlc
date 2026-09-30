---
type: ADR
title: 'ADR-028: TanStack Tableを採用する'
description: table state と row model の重複実装を避けるため、headless な TanStack Table を採用する決定。
tags: [adr, frontend, ui, table]
---

# ADR-028: TanStack Tableを採用する

## Status

Proposed

## Date

2026-09-29

## Context

FrontendはReact 19、TanStack Router、TanStack Query、TanStack Formを採用している。
今後の一覧画面では、sorting、filtering、pagination、row selection、column visibilityなどのtable stateを扱う可能性がある。

単純な読み取り専用表はnative `<table>` だけで実装できる。
一方、複数のtable stateを画面ごとに手書きすると、state更新、列定義、row modelの実装が重複する。

TanStack TableはUIを描画しないheadless libraryであり、table stateとrow modelを提供する。
描画とアクセシビリティはnative table要素と、このプロジェクトのshadcnおよびTailwind CSSで管理できる。

## Decision

Reactのtable state管理に `@tanstack/react-table` 9.xを使う。
依存versionは `frontend/package.json` へ固定する。

TanStack Table専用の共通wrapperやData Grid componentは先行して作らない。
最初の一覧画面で必要なcolumn definitionとrow modelだけを構成し、単純な表ではnative `<table>` を直接使う。

大量データのsorting、filtering、paginationはSpring Boot APIの責務とし、Frontendへ全件を取得して処理しない。
小規模な表示データに限り、TanStack Tableのclient-side row modelを使う。

## Consequences

### Positive

- sorting、filtering、pagination、selectionなどのtable stateを型安全に管理できる。
- UI libraryを固定せず、native table semanticsと既存design systemを維持できる。
- TanStack Queryから取得したrow dataと組み合わせやすい。

### Negative

- 単純な表にも適用すると、native tableだけの実装より設定量が増える。
- headless libraryなので、markup、responsive表示、keyboard操作、アクセシビリティは各画面で設計する必要がある。
- major version更新時にcolumn definitionとrow model APIの互換性を確認する必要がある。

### Neutral

- 依存は先に固定するが、利用画面がないためcomponentと利用例は追加しない。
- virtualizationとspreadsheet相当の編集機能はこの判断に含めない。

## Alternatives Considered

### native tableだけを使う

- **Description**：React stateと標準の `<table>` だけで一覧画面を実装する。
- **Pros**：追加依存とtable固有の設定が不要になる。
- **Cons**：複数のtable stateが必要になると、画面ごとの状態管理とrow処理が重複する。

### Data Grid componentを採用する

- **Description**：描画、編集、virtualizationを含む完成済みData Gridを使う。
- **Pros**：高度な一覧機能を短期間で追加できる。
- **Cons**：現在は高度な編集とvirtualizationの要件がなく、UIとアクセシビリティの制約が増える。

## References

- [TanStack Table](https://tanstack.com/table/latest)
- [frontend/package.json](../../frontend/package.json)
