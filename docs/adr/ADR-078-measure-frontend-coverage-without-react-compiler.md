---
type: ADR
title: 'ADR-078: フロントエンドの coverage を React Compiler を外して測る'
description: React Compiler が足す memo の分岐が branch coverage の分母に入る問題に対し、テストは Compiler を有効にして実行し、coverage の計測だけ Compiler を外す決定。
tags: [adr, testing, quality, frontend]
---

# ADR-078: フロントエンドの coverage を React Compiler を外して測る

## Status

Proposed

## Date

2026-10-12

## Context

[ADR-026](ADR-026-enable-react-compiler-with-oxc.md) で React Compiler を有効にしたため、テストも Compiler を通したコードで実行している。
Compiler は memo のために、依存の値が前回と同じかを比べる分岐を生成する。
V8 の coverage は、手書きではないこの分岐も分母に数える。

[ADR-076](ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md) でファイルごとの branch の下限を 60% にしたところ、Pull Request #199 の CI が失敗した。
失敗したファイルは、確定と取消の hook である。
このファイルの branch は 30 個で、coverage は 17 個（56.66%）だった。
同じファイルを Compiler なしで測ると、branch は 6 個で、すべて通っていた。
テストが足りなかったのではなく、Compiler の分岐が分母を増やしていた。

全体で見ても、Compiler ありの branch は 743/802 で、Compiler なしは 384/407（94.34%）である。
計測している branch の半分ほどが Compiler の生成物になる。
Compiler なしの計測でも、全体の下限（branch 88%、line 95%）とファイルごとの下限（branch 60%、line 65%）は満たす。

このままでは、Compiler の生成物の都合で CI が落ちるたびに、テストかコードを coverage に合わせて変えることになる。
Compiler のバージョンを上げただけでも、分母が変わって失敗しうる。

## Decision

coverage の計測では React Compiler を外し、手書きのコードだけを分母にする。
テストの実行は、本番ビルドと同じく Compiler を有効にしたまま、coverage なしで 1 回行う。

`task fe-coverage` は次の 2 つを順に実行する。

- Compiler を有効にして `vp test run` を実行し、Compiler を通したコードの振る舞いを検証する。
- 環境変数 `FRONTEND_REACT_COMPILER=false` を付けて `vp test run --coverage` を実行し、coverage を測る。

`vite.config.ts` は、`FRONTEND_REACT_COMPILER` が `false` のときだけ Compiler を無効にする。
下限の値は ADR-076 のまま変えない。

## Consequences

### Positive

- 下限が手書きのコードの検証量だけを表し、Compiler の生成物に左右されない。
- Compiler を更新しても、coverage が動かない。
- Compiler を通したコードでもテストを実行するので、Compiler の変換で壊れる振る舞いは引き続き検出できる。

### Negative

- テストを 2 回実行するので、`task fe-coverage` が約 12 秒長くなる。
- coverage を測ったコードは、本番に出るコードと同一ではない。Compiler が生成するコードの経路は、coverage では確かめられない。

### Neutral

- `FRONTEND_REACT_COMPILER` は計測のための切替で、開発や本番ビルドでは使わない。
- Compiler の診断は ADR-026 のとおり、必要な調査のときだけ有効にする。

## Alternatives Considered

### 選択肢1: ファイルごとの下限を下げる

- **Description**：Compiler の分岐を含んでも通るように、下限を 60% から下げる。
- **Pros**：設定を変えるだけで済む。
- **Cons**：テストのないファイルも見逃すようになり、ADR-076 の目的を損なう。

### 選択肢2: Compiler の分岐に当てるテストを書く

- **Description**：依存の値が変わるテストを足し、memo の分岐を通す。
- **Pros**：計測の方法を変えない。
- **Cons**：利用者から見える振る舞いではなく、Compiler の内部実装をテストすることになる。Compiler を更新すると壊れる。

### 選択肢3: coverage の除外コメントを付ける

- **Description**：`/* v8 ignore */` で Compiler の分岐を除く。
- **Pros**：計測の方法を変えない。
- **Cons**：Compiler が生成する分岐にコメントを付けられない。

### 選択肢4: テストも Compiler を外して実行する

- **Description**：テストの実行を 1 回にし、Compiler を常に外す。
- **Pros**：テストの実行時間が増えない。
- **Cons**：Compiler が壊す振る舞いを、テストで検出できなくなる。

## References

- [ADR-026](ADR-026-enable-react-compiler-with-oxc.md)
- [ADR-076](ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)
- [React Compiler](https://react.dev/learn/react-compiler)
