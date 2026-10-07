---
type: ADR
title: 'ADR-071: 作業で得た学びを 5 つの層へ還元し、上の層を優先する'
description: 作業のたびに得た学びを、アーキテクチャ、型と静的検査、振る舞いのテスト、エージェント向けの docs、人のレビューの順で上の層へ還元する規律を、全タスク共通に据える決定。
tags: [adr, principles, continuous-improvement, ai-assisted-development]
---

# ADR-071: 作業で得た学びを 5 つの層へ還元し、上の層を優先する

## Status

Proposed

## Date

2026-10-08

## Context

このリポジトリは、コーディングエージェントが実装の多くを担う前提で、作業を重ねるほど開発の仕組みそのものが育つ状態（ソフトウェアファクトリー）を目指している。
エージェントは会話をまたいで学びを保持しないため、作業で得た学びはコード、検査、docs に残さない限り次の作業へ引き継がれない。

学びを仕組みへ戻す規則は、すでに複数の文書に散らばっている。
[アーキテクチャの制約の自動検証](../principles/architecture-fitness-functions.md)は制約を検査にすることを、[レビューで人が確認する範囲](../code-review/review-scope.md)はレビューの指摘を規約や検査へ戻すことを定めている。
しかし、どの作業でも学びを還元すること、還元先をどの順で選ぶかを定めた文書はなかった。
そのため、不具合の修正やエージェントの誤りのように、レビューを経ない学びは仕組みへ戻らず、同じ誤りが繰り返され得る。

還元先の層は、誤りを止める確実さが異なる。
アーキテクチャで誤った書き方をできなくすれば、読み手が人でもエージェントでも誤りは生じない。
型、静的検査、テストは誤りを書いた後で機械的に検出する。
docs は読まれ、正しく解釈されたときだけ効き、人のレビューはレビュアーの注意に依存する。

## Decision

作業で得た学びは、その作業の中で、アーキテクチャ、型と静的検査、振る舞いのテスト、エージェント向けの docs、人のレビューの 5 つの層へ還元する。
還元先は上の層から順に検討し、最初に成り立つ層を選ぶ。
規約の正文は[学びを仕組みへ還元し続ける](../principles/continuous-reinforcement.md)に置き、全タスクで読まれるようにルートの `AGENTS.md` から案内する。

## Consequences

### Positive

- 不具合の修正やエージェントの誤りのように、レビューを経ない学びも仕組みへ戻る。
- 上の層への還元が積み重なるほど、エージェントが読むべき docs と人がレビューで確かめる観点が減る。
- 散らばっていた還元の規則を、一つの文書からたどれる。

### Negative

- 元の作業に、検査やテストを追加する分の変更が加わり、Pull Request が大きくなる。
- 検査が増えるほど CI の実行時間と、検査そのものの保守が増える。
- 想定だけの誤りにも検査を足す過剰な還元が起き得るため、規約で実際に起きた誤りに限っている。

### Neutral

- `AGENTS.md` は `docs/agents/` 領域の steering を兼ねるが、全タスクで読まれるため、領域をまたぐこの規律の案内も置く。案内にとどめ、本文は書かない（[ADR-038](ADR-038-route-steering-to-docs-knowledge.md)）。
- 各層の検査やテストの作り方は、引き続き各領域の docs が正文を持つ。

## Alternatives Considered

### 選択肢1: アーキテクチャの制約の自動検証に追記する

- **Description**：`docs/principles/architecture-fitness-functions.md` に 5 つの層の優先順位を追記する。
- **Pros**：文書が増えない。
- **Cons**：同文書は自動検査の作り方を定め、docs やレビューへの還元と還元の契機を扱わない。追記すると 1 ファイルに責務が混ざる。

### 選択肢2: `AGENTS.md` に規律の本文を書く

- **Description**：全タスクで読まれる `AGENTS.md` に、層の一覧と契機を直接書く。
- **Pros**：docs を開かなくても規律が届く。
- **Cons**：steering に規約の本文を置かない [ADR-038](ADR-038-route-steering-to-docs-knowledge.md) に反し、iwe の検索とリンク検査の対象外になる。

### 選択肢3: 常時読み込みの steering `ponytail` に追記する

- **Description**：「非自明なロジックには検査を一つ残す」と定める `ponytail` に、還元の案内を足す。
- **Pros**：既存の指針の近くに置ける。
- **Cons**：`ponytail` は外部で作られた指針であり、このリポジトリでは変更しない。

### 選択肢4: 還元の手順を Runbook にする

- **Description**：層ごとの検査やテストの追加手順を Runbook にまとめる。
- **Pros**：手順を一箇所で読める。
- **Cons**：各層の手順は各領域の docs に正文があり、重複する。

## References

- [学びを仕組みへ還元し続ける](../principles/continuous-reinforcement.md)
- [アーキテクチャの制約の自動検証](../principles/architecture-fitness-functions.md)
- [レビューで人が確認する範囲](../code-review/review-scope.md)
- [ADR-038: steering をナビゲーションに限定し、規約の正文を docs/ に置く](ADR-038-route-steering-to-docs-knowledge.md)
