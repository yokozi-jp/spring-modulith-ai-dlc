---
type: ADR
title: 'ADR-088: フロントエンドで useEffect を禁止し、Oxlint で止める'
description: useEffect、useLayoutEffect、useInsertionEffect を禁止し、ローカルの Oxlint 規則で検出する。外部 system との同期は ref callback と useSyncExternalStore で書く。
tags: [adr, frontend, react, lint]
---

# ADR-088: フロントエンドで useEffect を禁止し、Oxlint で止める

## Status

Proposed

## Date

2026-11-17

## Context

[フロントエンドのルーティングと状態管理](../frontend/routing-and-state.md)は、Effect を「Reactの外にあるsystemとcomponentを同期するときだけ」使うと定めていた。
派生 state、event handler で済む処理、state の初期化、Effect の連鎖、server state の取得は、Oxlint と React Doctor が部分的に検出する。
一方、この規約は書き手の判断に頼る。生成した API client 関数を Effect から呼ぶ形などは検出できず、review で確認していた。

実際の Effect 7 箇所のうち、`document.title` の設定が5箇所、エラーの報告が1箇所、`pageshow` の購読が1箇所だった。
どれも Effect でなくても書ける。
`document.title` は React 19 の `<title>` で書ける。
エラーの報告と `pageshow` の購読は、ref callback（React 19 は cleanup 関数を返せる）で書ける。

## Decision

`useEffect`、`useLayoutEffect`、`useInsertionEffect`、`useEffectEvent` を、フロントエンドのコードで禁止する。
ローカルの Oxlint 規則 `react-rules/no-effect`（`frontend/lint/react-rules.js`）が、`react` からの import と再 export、default import の名前の参照、分割代入を検出し、`task fe-check` と CI で失敗にする。

代わりの書き方は[規約](../frontend/routing-and-state.md#effectの禁止)に置く。
`useEffectEvent` は Effect の中でしか呼べないため、Effect と一緒に禁止する。

## Consequences

### Positive

- 書き手の判断に頼っていた規約を、機械で止められる。
- Effect が招く余分な render と、古い値の表示を作り込めなくなる。

### Negative

- ref callback は要素への取り付けに紐づくため、要素を持たない購読には `useSyncExternalStore` か別の形が要る。
- 規則は `react` からの import に結び付けて判定する。自前の関数で包んだ Effect は見逃す。
- ref callback に移した処理には、Effect 向けの検査（`react/set-state-in-effect` など）が効かない。規約で、ref callback の中の state 更新と取得を禁じ、review で確認する。
- `<title>` を持つ画面が unmount されると、title は `index.html` の既定の値に戻る。以前は前の画面の title が残っていた。
- どうしても Effect が要る場面（第三者 widget の初期化など）は、行単位の `oxlint-disable` と理由で許すか、この ADR を見直す。

### Neutral

- React Doctor の Effect 向けの検査は残す。

## Alternatives Considered

### 選択肢1: 現行の規約（外部 system との同期だけ許す）を保つ

- **Description**：docs の規約と、既存の検査だけで運用する。
- **Pros**：変更がない。
- **Cons**：規約を守ったかは review に頼り、実際の Effect の多くは Effect が要らない処理だった。

### 選択肢2: 組み込みの `no-restricted-imports` で禁止する

- **Description**：`useEffect` を import の制限に加える。
- **Pros**：新しい規則のファイルを要さない。
- **Cons**：`React.useEffect` のような default import 経由の参照と分割代入を検出できない。

## References

- [フロントエンドのルーティングと状態管理](../frontend/routing-and-state.md)
- [React: You Might Not Need an Effect](https://react.dev/learn/you-might-not-need-an-effect)
- [React: ref callback の cleanup](https://react.dev/reference/react-dom/components/common#ref-callback)
