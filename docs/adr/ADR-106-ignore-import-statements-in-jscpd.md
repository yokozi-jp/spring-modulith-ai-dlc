---
type: ADR
title: 'ADR-106: jscpd は import 文を比べず、閾値を実測の 1.86% に合わせて 2.0% にする'
description: クラスの役割とテストの規約が求める import の並びが重複率を押し上げ、閾値を上げ続ける問題を避けるため、jscpd の比較から import 文を外し、閾値を 2.0% に改める決定。
tags: [adr, quality, ci, lint]
---

# ADR-106: jscpd は import 文を比べず、閾値を実測の 1.86% に合わせて 2.0% にする

## Status

Proposed

## Date

2026-11-21

## Context

[ADR-074](ADR-074-raise-jscpd-threshold-for-class-role-boilerplate.md) は、クラスの役割とテストの規約が求める定型の重複を通すため、jscpd の閾値を 4.0% にした。
そのとき、業務機能が増えて 4.0% を超えたら、閾値をさらに上げる前に検出の方式を見直すと決めた。

Pull Request #552（テナントの所属の管理）で、この合図が出た。
`main` の重複率は 3.88% で、この変更を merge した結果は 4.07% になり、`lint-duplicates` が失敗した。
`main` の `.jscpd.json` と同じ設定のまま、`import` 文だけを比べない設定で測ると、`main` が 1.86%、merge の結果が 1.85% で、この変更は import 以外の重複をほとんど足していなかった。
増えた重複は、同じ役割のクラスとテストが持つ import の並び（`org.springframework.test.web.servlet.request.MockMvcRequestBuilders` の static import の並びなど）であり、規約が求める定型である。

import の並びは、型の依存を宣言するだけで、共通化できる処理ではない。
import の重複を理由に基底クラスや共通の部品を作ると、ADR-074 が退けたとおり、モジュールの境界を越える依存が増える。
一方、閾値を上げ続けると、import 以外の手書きの重複を検出できなくなる。

## Decision

`.jscpd.json` の `ignorePattern` に `\nimport [^;]*;` を足し、Java と TypeScript の `import` 文を jscpd の比較から外す。
`threshold` は、この方式での `main` の実測 1.86% を通す 2.0% にする。
検査の対象、除外する生成コード、Task を共通の入口にすることなど、[ADR-035](ADR-035-adopt-jscpd-and-knip-quality-gates.md) のほかの決定は変えない。

ADR-074 は、閾値の値と検出の方式の両方を改めるため、この ADR で置き換える。

手書きの重複は、これまでどおり閾値の余裕に頼らず、[重複の共通化と変更のしやすさ](../principles/duplication-and-change.md)の基準で共通化する。
`import` 文を除いた重複が 2.0% を超えたときは、閾値を上げる前に、重複を解消する。

## Consequences

### Positive

- 規約が求める import の並びのために、Pull Request が失敗しない。
- 閾値が処理の重複に近づき、import 以外の小さな重複の追加を、ADR-074 の 4.0% のときより検出しやすくなる。
- ADR-074 の見直しの合図に従い、閾値を上げずに検出の方式を直せる。

### Negative

- 同じ import の並びを持つファイルの重複は、検出しない。
- `ignorePattern` は正規表現で、行頭の `import` から最初の `;` までを消す。`;` を省いた import 文を書くと、次の `;` までを消す。Prettier 系の整形が `;` を付けるため、現在のコードでは起きない。

### Neutral

- 閾値を下げる方向の見直しは、ADR-035 と同じく、重複を解消した変更で行う。

## Alternatives Considered

### Alternative 1: 閾値を 4.0% より上げる

- Description：実測に合わせて、4.5% などにする。
- Pros：設定が 1 行で済む。
- Cons：業務機能を足すたびに同じ作業が生じる。import 以外の重複も検出しにくくなる。

### Alternative 2: import の並びを共通化する

- Description：基底クラスや共通のテスト部品へ移す。
- Pros：重複が減る。
- Cons：モジュールが共通の部品に依存する（ADR-074 の Alternative 1 と同じ）。

### Alternative 3: Pull Request を小さく分けて、重複を分散する

- Description：重複の追加を複数の Pull Request に分ける。
- Pros：設定を変えずに済む。
- Cons：`main` の余裕は 0.12% しかなく、次の Pull Request でも同じ問題が起きる。

## References

- [ADR-035: jscpd と Knip を品質ゲートに採用する](ADR-035-adopt-jscpd-and-knip-quality-gates.md)
- [ADR-074: jscpd の閾値を、クラスの役割の定型を含む実測の 3.93% に合わせて 4.0% にする](ADR-074-raise-jscpd-threshold-for-class-role-boilerplate.md)
- [重複の共通化と変更のしやすさ](../principles/duplication-and-change.md)
- `.jscpd.json`
