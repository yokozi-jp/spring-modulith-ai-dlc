---
type: ADR
title: 'ADR-082: フロントエンドの名前付きの関数を関数宣言にそろえ、Oxlint の func-style で強制する'
description: フロントエンドで名前を付ける関数を関数宣言にそろえ、型注釈付きの変数だけアロー関数を許す決定。アロー関数にそろえる案、Airbnb の名前付き関数式に従う案、component の中を自由にする案を退けた理由を記す。
tags: [adr, frontend, lint, oxlint, react, typescript]
---

# ADR-082: フロントエンドの名前付きの関数を関数宣言にそろえ、Oxlint の func-style で強制する

## Status

Proposed

## Date

2026-10-20

## Context

フロントエンドの名前付きの関数には、関数宣言と、アロー関数を変数に代入する書き方が混在していた。
トップレベルの手書きの関数は関数宣言がほとんどで、component や Hook の中の名前付き関数と、e2e、設定ファイル、テストの補助関数にアロー関数が残っていた。
`func-style` は [ADR-034](ADR-034-pin-frontend-runtime-and-enforce-oxlint-categories.md) の全カテゴリ強制の例外として off にしており、どちらの書き方も lint を通っていた。

どちらへそろえるかは、一般に通用する書き方に合わせる方針で決める。
一次資料の推奨は次のとおりである。

- [Google TypeScript Style Guide](https://google.github.io/styleguide/tsguide.html) は、名前付きの関数に関数宣言を優先させ、明示的な型注釈が必要な場合にアロー関数を許す。コールバックはアロー関数にし、`function` 式を禁じる。
- [React 公式ドキュメント](https://react.dev/learn/your-first-component) は、component を `function` で宣言し、component の中のイベントハンドラも `function handleClick()` と書く。
- [Airbnb React Style Guide](https://github.com/airbnb/javascript/blob/master/react/README.md) は、component を関数宣言にし、`const Listing = ({ hello }) => (...)` を退ける。
  一方、[Airbnb JavaScript Style Guide](https://github.com/airbnb/javascript/blob/master/README.md) の 7.1 は、巻き上げで定義前に参照できることを理由に、関数宣言ではなく名前付き関数式を求める。

Oxlint の `func-style` は `"declaration"` と `allowTypeAnnotation` を持ち、コールバックを検査しない。
コールバックのアロー関数は、`style` カテゴリの `prefer-arrow-callback` がすでに強制している。

## Decision

フロントエンドで名前を付ける関数は、トップレベルでも component や Hook の中でも関数宣言で書く。
変数に関数の型を注釈するときだけ、アロー関数を変数に代入してよい。
その場で渡すコールバックはアロー関数で書く。

`frontend/vite.config.ts` の lint で `func-style` を `["error", "declaration", { allowTypeAnnotation: true }]` にする。
振る舞いは `frontend/lint/lint-config.test.js` の fixture で固定する。
書き方は [Lint とテストのリファレンス](../tooling/lint-and-test.md) に置く。

## Consequences

### Positive

- 名前付きの関数の書き方が一つになり、レビューで書き方を指摘する必要がなくなる。
- React 公式ドキュメント、Google TypeScript Style Guide、shadcn/ui の生成コードと同じ書き方になり、外部の例をそのまま読み書きできる。
- 関数宣言は巻き上がるため、主役の component を先頭に置き、補助関数を下に書ける。
- assertion function とオーバーロードを、変数の型注釈なしで書ける。

### Negative

- 既存のアロー関数の名前付き関数（e2e、設定ファイル、テスト、lint の fixture を含む）を書き換える。
- 式一つで済む関数も `return` を持つ本体になり、行数が増える。`e2e/logout.spec.ts` は `max-lines` に近く、一度だけ使う補助関数を呼び出し側へ戻した。
- 関数宣言は再代入できる。再代入は有効なままの `no-func-assign` が検出する。

### Neutral

- Orval の生成物はアロー関数のままである。生成物は lint の対象外なので影響しない。
- 型注釈付きの例外は、イベントハンドラの型やライブラリの callback 型で引数を推論させる場面のために残す。

## Alternatives Considered

### 選択肢1: アロー関数にそろえる

- **Description**：名前付きの関数をすべて `const f = () => {}` で書き、`func-style` を `"expression"` にする。
- **Pros**：コールバックと見た目がそろう。`const` で束縛するので再代入できない。
- **Cons**：参照した一次資料のどれも名前付きの関数をアロー関数にすることを推奨せず、既存のトップレベルの関数のほとんどを書き換えることになるため退けた。

### 選択肢2: Airbnb JavaScript Style Guide の名前付き関数式に従う

- **Description**：`const short = function longName() {}` のように、名前付きの関数式を変数に代入する。
- **Pros**：巻き上げを避け、スタックトレースに式の名前を確実に出せる。
- **Cons**：名前を二重に書く。スタックトレースの懸念は、ES2015 以降の関数名の推論で今のビルドでは生じない。Airbnb 自身も React component には関数宣言を求めるため退けた。

### 選択肢3: トップレベルだけ関数宣言にし、component の中は自由にする

- **Description**：Google TypeScript Style Guide の文言どおり、入れ子の関数はどちらの書き方も許す。
- **Pros**：component の中の既存のアロー関数を書き換えずに済む。
- **Cons**：`func-style` はスコープを区別しないため、強制するには自作の lint 規則が要る。強制しなければ書き方は統一されない。React 公式ドキュメントは中も関数宣言で書くため、そろえても標準から外れない。

## References

- [ADR-034: Frontend ランタイムを固定し Oxlint 全カテゴリを強制する](ADR-034-pin-frontend-runtime-and-enforce-oxlint-categories.md)
- [Lint とテストのリファレンス](../tooling/lint-and-test.md)
- [Oxlint func-style](https://oxc.rs/docs/guide/usage/linter/rules/eslint/func-style.html)
- [Oxlint prefer-arrow-callback](https://oxc.rs/docs/guide/usage/linter/rules/eslint/prefer-arrow-callback.html)
