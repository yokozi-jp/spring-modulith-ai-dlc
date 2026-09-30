---
type: Architecture Decision Record
title: 'ADR-039: Matt Pocock のスキルフローを導入する'
description: Matt Pocock の開発フローで使うスキルを固定リビジョンから導入し、プロジェクト固有の使い方を Runbook へ記録する決定。
tags: [adr, workflow, agent-skills, matt-pocock]
---

# ADR-039: Matt Pocock のスキルフローを導入する

## Status

Proposed

## Date

2026-09-30

## Context

[ADR-037](ADR-037-remove-aidlc-framework.md) は AI-DLC を廃止し、pstack や Matt Pocock のスキル群を試す方針を示したが、後継の開発フローは決めていない。
利用者は、実装前の対話から仕様化、チケット分割、実装、レビューへ進む Matt Pocock のフローを保存し、その説明に登場するスキルを導入したい。

外部スキルはエージェントへ手順を与え、一部はファイル変更、Issue tracker 操作、サブエージェント実行、コミットを指示する。
上流の更新を無条件に取り込むと、プロジェクトの規約や権限境界が変わる可能性がある。
また、[ADR-038](ADR-038-route-steering-to-docs-knowledge.md) は、プロジェクトの繰り返し手順を docs の Runbook に置くと定めている。

## Decision

Matt Pocock の開発フローを採用し、[Matt Pocock スキル運用 Runbook](../agents/matt-pocock-skills/workflow.md) に利用順序、例外時の入口、コンテキストの切り方を記録する。

利用者が提示した説明に登場するスキルと、その直接依存である `grilling` と `codebase-design` をワークスペースへ導入する。
上流は `mattpocock/skills` のコミット `d81f3a183412e71a5b1e84ca21bc1a35eea03a60` に固定する。
取得には `skills` CLI 1.7.0 を使い、実体を `.agents/skills/`、Kiro の検出用リンクを `.kiro/skills/`、内容ハッシュを `skills-lock.json` に置く。

上流の `SKILL.md` と同梱リソースは、更新差分を追跡できるよう無変更で保持する。
`docs/agents/matt-pocock-skills/workflow.md` をプロジェクト固有の利用方法の正文とし、上流スキルとプロジェクト規約が競合した場合は docs の規約と ADR を優先する。
特に ADR の書式と作成条件は `docs/adr/conventions.md`、docs の作成方法は `docs/writing/` と documentation authoring の規約に従う。

更新は固定コミットを明示的に変更する Pull Request で行う。
`skills update` による無確認の最新版追従は行わず、上流差分、同梱スクリプト、ロックファイル、Runbook への影響をレビューする。

## Consequences

### Positive

- 実装前の意思決定から実装とレビューまで、再利用できる開発フローを共有できる。
- スキルの取得元と内容を固定し、同じワークスペースを再現できる。
- 上流の手順とプロジェクト固有の使い方を分けるため、更新差分とローカル規約の所在が明確になる。

### Negative

- 導入するスキル数が増え、Kiro が読み込むスキル名と description の量が増える。
- 上流スキルには Kiro 固有ではないツール名や文書規約が含まれ、実行時にプロジェクト規約との調整が必要になる。
- 上流更新のたびに、20スキルと同梱リソースの差分を確認する作業が生じる。

### Neutral

- `setup-matt-pocock-skills` は導入するが、この変更では実行しない。
- `GLOSSARY.md` は最初の用語を記録するときに作成し、空の文書は先に作らない。
- スキルの導入はワークフローの追加であり、アプリケーションの実行時依存には含めない。

## Alternatives Considered

### Matt Pocock の全スキルを導入する

- **Description**：上流にある37スキルをすべて導入する。
- **Pros**：`ask-matt` が案内できる全フローを利用できる。
- **Cons**：利用者が指定していない執筆、教育、Claude Code 固有のスキルまで入り、発火条件と更新範囲が広がる。

### メインフローのスキルだけを導入する

- **Description**：`grill-with-docs` から `implement` までの通常フローだけを導入する。
- **Pros**：導入数と確認範囲を小さくできる。
- **Cons**：利用者が保存した例外時の入口を実行できず、記録した Runbook と利用可能なスキルが一致しない。

### Kiro の Spec だけを使う

- **Description**：外部スキルを導入せず、Kiro の requirements、design、tasks を使う。
- **Pros**：Kiro 固有機能だけで運用でき、外部更新を追跡する必要がない。
- **Cons**：利用者が求める `grill-with-docs`、`ask-matt`、例外時の入口を利用できない。

## References

- [Matt Pocock スキル運用 Runbook](../agents/matt-pocock-skills/workflow.md)
- [ADR-037: AI-DLC フレームワークの利用をやめる](ADR-037-remove-aidlc-framework.md)
- [ADR-038: steering をナビゲーションに限定し、規約の正文を docs/ に置く](ADR-038-route-steering-to-docs-knowledge.md)
- [Matt Pocock skills](https://github.com/mattpocock/skills/tree/d81f3a183412e71a5b1e84ca21bc1a35eea03a60)
