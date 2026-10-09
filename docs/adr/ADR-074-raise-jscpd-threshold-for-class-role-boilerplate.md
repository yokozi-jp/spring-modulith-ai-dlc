---
type: ADR
title: 'ADR-074: jscpd の閾値を、クラスの役割の定型を含む実測の 3.93% に合わせて 4.0% にする'
description: 参照業務機能の手書きの重複を除いた後も残る、クラスの役割とテストの規約が求める定型の重複を通すため、ADR-035 の jscpd の閾値 3.2% を 4.0% に改める決定。
tags: [adr, quality, ci, lint]
---

# ADR-074: jscpd の閾値を、クラスの役割の定型を含む実測の 3.93% に合わせて 4.0% にする

## Status

Proposed

## Date

2026-10-09

## Context

[ADR-035](ADR-035-adopt-jscpd-and-knip-quality-gates.md) は、jscpd の導入時の重複率 3.19% から閾値を 3.2% に固定し、既存の clone を解消する変更で段階的に下げると決めた。
その後、参照業務機能として product、ordering、payment の 3 つのモジュールを加え、重複率が 3.2% を超えた。

手書きの重複（同じ検査の手順、同じテストの準備、同じ文字列の繰り返し）を共通化して除いても、重複率は 3.93% に残った。
残りの重複は、[クラスの役割と命名](ADR-050-define-backend-class-roles-and-naming.md)の規約が、モジュールごとに同じ形のクラスとテストを置くよう求めることから生じる。
同じ役割のクラスは、同じ import、注釈、フィールド、コンストラクタの並びを持ち、jscpd はこれをトークン列の重複として数える。
この定型を共通の基底クラスや共通の部品へ移すと、モジュールの境界を越えた依存が増え、[重複の共通化と変更のしやすさ](../principles/duplication-and-change.md)の基準にも合わない。

## Decision

ADR-035 の決定のうち、jscpd の閾値の値だけを改め、`.jscpd.json` の `threshold` を 4.0% にする。
4.0% は、手書きの重複を除いた後の実測 3.93% を通す値である。
検査の対象、除外する生成コード、Task を共通の入口にすることなど、ADR-035 のほかの決定は変えない。

手書きの重複は、これまでどおり閾値の余裕に頼らず共通化する。
業務機能が増えて、規約の定型の重複で 4.0% を超えたときは、閾値をさらに上げる前に、検出の方式（定型のファイルや最小のトークン数）を見直す。
この見直しの合図は、`Taskfile.yml` の `lint-duplicates` の `ponytail:` のコメントにも書く。

## Consequences

### Positive

- 規約が求める定型のために、モジュールの境界を越える共通化をしなくて済む。
- ADR-035 の閾値と `.jscpd.json` の値が食い違わない。

### Negative

- 3.2% のときより、小さな手書きの重複の追加を検出しにくくなる。
- 業務機能を足すたびに定型の重複が増えるため、閾値か検出の方式を見直す作業が繰り返し生じる。

### Neutral

- 閾値を下げる方向の見直しは、ADR-035 と同じく、重複を解消した変更で行う。

## Alternatives Considered

### Alternative 1: 閾値を 3.2% のままにし、定型を共通化する

- Description：クラスの役割ごとの import、注釈、フィールドの並びを、基底クラスや共通の部品へ移す。
- Pros：閾値を上げずに済む。
- Cons：モジュールが共通の部品に依存し、各モジュールを独立して変えにくくなる。

### Alternative 2: 定型のクラスを検査の対象から外す

- Description：`.jscpd.json` の `ignore` に、クラスの役割ごとのファイルの形を加える。
- Pros：閾値を上げずに済む。
- Cons：同じファイルにある手書きの重複も検出しなくなる。

## References

- [ADR-035: jscpd と Knip を品質ゲートに採用する](ADR-035-adopt-jscpd-and-knip-quality-gates.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [重複の共通化と変更のしやすさ](../principles/duplication-and-change.md)
- `.jscpd.json`
- `Taskfile.yml`
