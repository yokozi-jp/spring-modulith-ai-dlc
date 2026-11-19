---
type: ADR
title: 'ADR-098: 用途を制限するライセンスを理由に React Doctor を外す'
description: React Doctor の Modified MIT ライセンスが AI 利用に書面の許可を求めるため、react-doctor を外し、React 固有の静的検査を Oxlint に一本化する。
tags: [adr, frontend, lint, license]
---

# ADR-098: 用途を制限するライセンスを理由に React Doctor を外す

## Status

Proposed

## Date

2026-11-19

## Context

`react-doctor` 0.9.14 を `frontend/apps/web` の devDependency に入れ、`task fe-doctor` と Frontend CI で動かしていた。
ライセンスは Modified MIT で、次の 2 つの利用に、著作権者の書面の許可を求める（[LICENSE](https://unpkg.com/react-doctor@0.9.14/LICENSE)）。

1. ソフトウェアやその派生物を、機械学習のモデルや AI のシステムの学習、fine-tuning、評価のデータとして使うこと、またはそれらを学習や改善する自動のパイプラインの入力にすること。
2. ソフトウェアの価値に主に頼る有償のホスト型のサービスとして提供すること。

このリポジトリではエージェントが出力を読んでコードを直すため、1 に当たるかを確かめる必要があった（#356）。
別のリポジトリ（yokozi-jp/agent-software-factory）は、用途を制限するライセンスの道具は代替があれば採らないとして、react-doctor を入れないと決めている（同リポジトリの #225）。

確かめた事実は次のとおり。

- 条項 2 は、このリポジトリの使い方には当たらない。
- 条項 1 に当たるかは法務の解釈になり、リポジトリの中の事実では決められない。
- Oxlint は `react` と `jsx-a11y` を含む全カテゴリを `error` で動かしている。
- 外す前の `task fe-doctor` は指摘ゼロだった。

## Decision

react-doctor を外す。許可は求めない。

- `frontend/apps/web` の devDependency と `react-doctor` script、`doctor.config.json` を削除する。
- Taskfile の `fe-doctor` と Frontend CI の `Diagnose React` ステップを削除する。
- React 固有の静的検査は、Oxlint の `react` と `jsx-a11y` の規則、ローカルの `react-rules` に一本化する。
- 条項 1 に当たるかの判断はしない。当たるかどうかに関わらず、代替のある道具でライセンスの疑いを抱えない。

## Consequences

### Positive

- 用途を制限するライセンスの疑いが、依存から消える。
- Frontend CI から、実行に最大 15 分かかるジョブが消える。
- lockfile から react-doctor と、それだけが使う推移的依存が消える。

### Negative

- react-doctor 固有の規則（Oxlint に同等の規則がないもの）による検出を失う。外す前の指摘はゼロで、今の検出の低下は確認していない。
- ADR-088 の Effect 禁止は、Oxlint のローカル規則（`react-rules/no-effect`）が担う。React Doctor の Effect 向けの検査は補助を失う。

### Neutral

- 将来、React 固有の検査を足すときは、ライセンスが用途を制限しない道具から選ぶ。
- 著作権者から書面の許可が得られれば、再導入を見直せる。

## Alternatives Considered

### 選択肢1: 著作権者に許可を求めて使い続ける

- **Description**：`founders@million.dev` に書面の許可を求める。
- **Pros**：検査を失わない。
- **Cons**：返答を待つ間、疑いが残る。代替があるのに、許可に依存する道具を抱える。

### 選択肢2: 条項 1 に当たらないと解釈して使い続ける

- **Description**：エージェントが出力を読むだけで、学習の入力ではないと読む。
- **Pros**：変更が要らない。
- **Cons**：法務の解釈を、確認なしに前提にする。

## References

- Issue #356
- [react-doctor LICENSE](https://unpkg.com/react-doctor@0.9.14/LICENSE)
- yokozi-jp/agent-software-factory #225
- [ADR-034](ADR-034-pin-frontend-runtime-and-enforce-oxlint-categories.md)
- [ADR-088](ADR-088-ban-react-effects-in-frontend.md)
