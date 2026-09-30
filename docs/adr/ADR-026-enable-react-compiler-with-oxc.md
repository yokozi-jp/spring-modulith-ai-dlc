---
type: Architecture Decision Record
title: 'ADR-026: OXCでReact Compilerを有効化する'
description: 手動 memoization の判断を不要にするため、OXC で React Compiler を有効化する決定。
tags: [adr, frontend, react, build]
---

# ADR-026: OXCでReact Compilerを有効化する

## Status

Proposed

## Date

2026-09-29

## Context

FrontendはReact 19とVite+を使い、`@vitejs/plugin-react` 6.1.1でJSXを変換している。 React componentの再描画を抑えるために手作業で `memo`、`useMemo`、`useCallback` を追加すると、依存配列とmemoizationの要否を開発者が継続して判断する必要がある。

React Compilerはbuild時にcomponentとhookを解析し、安全に適用できるmemoizationを自動生成する。 `@vitejs/plugin-react` 6.1.1は、optional peer dependencyの `oxc-transform-react` を追加して `react({ compiler: true })` を指定するOXC実装を提供している。

Babel実装を使う場合は `@rolldown/plugin-babel`、`@babel/core`、`babel-plugin-react-compiler` と追加plugin設定が必要になる。 現在のVite+はRolldownとOXCを既に利用しており、Babel経路を追加する要件はない。

## Decision

`oxc-transform-react` を固定した開発依存として追加し、Vite設定で `react({ compiler: true })` を指定してReact Compilerを有効化する。 React 19を対象にするため、`react-compiler-runtime` は追加しない。

Compilerの診断ログは常時有効にしない。 React規則の静的診断は既存のOxlint、React Doctor、TypeScript検査で行い、必要な調査時だけCompiler診断を有効化する。

## Consequences

### Positive

- React componentとhookへ安全に適用できるmemoizationをbuild時に自動生成できる。
- 手作業のmemoizationと依存配列を減らし、componentコードを単純に保ちやすい。
- Vite+と同じOXC系の変換を使い、Babel toolchainを追加せずに済む。

### Negative

- build時の変換が増え、CompilerまたはOXC実装の不具合がFrontend全体へ影響する可能性がある。
- React Compilerが対応できない書き方では最適化が適用されず、必要に応じてコード修正または明示的な除外が要る。
- `@vitejs/plugin-react` と `oxc-transform-react` の互換範囲を依存更新時に確認する必要がある。

### Neutral

- React Compilerは実行時frameworkを置き換えず、生成bundleのReact APIも変えない。
- 性能改善はcomponentの状態と描画頻度に依存するため、この導入だけで一定の改善量を保証しない。

## Alternatives Considered

### Babel版React Compilerを使う

- **Description**：`babel-plugin-react-compiler` をRolldownのBabel pluginから実行する。
- **Pros**：React公式のBabel pluginと同じ変換経路を使える。
- **Cons**：現在のVite+構成へBabel本体とRolldown連携を追加し、設定と依存が増える。

### React Compilerを使わない

- **Description**：必要な箇所だけ手作業でmemoizationする。
- **Pros**：build変換とCompiler依存が増えない。
- **Cons**：最適化の要否と依存配列を開発者がcomponentごとに保守する必要がある。

## References

- [React Compiler](https://react.dev/learn/react-compiler)
- [`@vitejs/plugin-react`](https://github.com/vitejs/vite-plugin-react/tree/main/packages/plugin-react)
- [frontend/vite.config.ts](../../frontend/vite.config.ts)
