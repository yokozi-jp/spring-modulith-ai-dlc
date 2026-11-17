---
type: ADR
title: 'ADR-039: Matt Pocock のスキルフローを導入する'
description: Matt Pocock のスキルのうち RGDD のフェーズ2と pstack にない用途で使うものを固定リビジョンから導入して skills-lock.json に記録し、このリポジトリで書く取得元のないスキルも同じ記録に載せる決定。
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

Proposed の間に、[ADR-084](ADR-084-adopt-rgdd-with-pstack-and-thermos.md) が開発の流れを RGDD の5つのフェーズへ移し、実装とレビューを pstack と thermos に任せることにした。
そこで、Decision を次の点で書き直した。

- 導入するスキルを、RGDD のフェーズ2と、pstack にない用途で使うものに絞った。
  外すスキルとその理由は ADR-084 にある。
- 取得と更新の手順の正文を、[スキルの取得元と更新](../agents/skill-sources.md)へ移した。
- このリポジトリで書く取得元のないスキルの記録の規則を、ADR-084 からこの ADR へ移した。

## Decision

Matt Pocock のスキルを、RGDD のフェーズ2と、pstack にない用途で使う。
使い方は [Matt Pocock スキル運用 Runbook](../agents/matt-pocock-skills/workflow.md)、取得と更新の手順は [スキルの取得元と更新](../agents/skill-sources.md)を正文とする。

次の13スキルを導入する。

- **フェーズ2**：`grill-with-docs` と、それが呼ぶ `grilling` と `domain-modeling`。
  対話で決めた内容を仕様とチケットに分ける `to-spec` と `to-tickets`。
- **フェーズ2に乗らない作業の入口**：`triage`、`wayfinder`、`research`、`prototype`、`to-questionnaire`。
  既存のコードから深くするモジュールの候補を探す `improve-codebase-architecture` と、それが呼ぶ `codebase-design`。
- **初回設定**：`setup-matt-pocock-skills`。

`retro` と、それが呼ぶ `writing-for-agents` は [ADR-082](ADR-082-adopt-retro-and-run-it-at-task-boundaries.md) で導入する。

上流は `mattpocock/skills` のコミット `d81f3a183412e71a5b1e84ca21bc1a35eea03a60` に固定する。
取得には `skills` CLI 1.7.0 を使い、実体を `.agents/skills/`、Kiro の検出用の相対シンボリックリンクを `.kiro/skills/`、取得元、固定コミット、内容ハッシュを `skills-lock.json` に置く。

上流の `SKILL.md` と同梱リソースは、更新差分を追跡できるよう無変更で保持する。
上流スキルとプロジェクト規約が競合した場合は docs の規約と ADR を優先する。
特に ADR の書式と作成条件は `docs/adr/conventions.md`、docs の作成方法は `docs/writing/` と documentation authoring の規約に従う。

更新は固定コミットを明示的に変更する Pull Request で行う。
`skills update` による無確認の最新版追従は行わず、上流差分、同梱スクリプト、ロックファイル、Runbook への影響をレビューする。

このリポジトリで書く取得元のないスキル（`java-best-practices`、`/create-verification-skill` が作る検証スキルなど）も、同じ配置で `skills-lock.json` に記録する。
項目は `sourceType` を `local` にして `note` に由来を書き、`ref` を持たず、スキルを直すたびに `computedHash` を今の内容に合わせる。
スキルに規約を書くときは、正文を docs に置き、スキルからは docs を指す。

## Consequences

### Positive

- 実装前の意思決定から仕様とチケットへ分けるまで、再利用できる流れを共有できる。
- スキルの取得元と内容を固定し、同じワークスペースを再現できる。
- 上流の手順とプロジェクト固有の使い方を分けるため、更新差分とローカル規約の所在が明確になる。
- このリポジトリで書いたスキルの編集も、内容ハッシュの差分として Pull Request に現れる。

### Negative

- 導入するスキル数が増え、Kiro が読み込むスキル名と description の量が増える。
- 上流スキルには Kiro 固有ではないツール名や文書規約が含まれ、実行時にプロジェクト規約との調整が必要になる。
- 上流更新のたびに、13スキルと同梱リソースの差分を確認する作業が生じる。
- このリポジトリで書いたスキルを直すたびに、`computedHash` を更新する手間がかかる。

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
- [スキルの取得元と更新](../agents/skill-sources.md)
- [ADR-037: AI-DLC フレームワークの利用をやめる](ADR-037-remove-aidlc-framework.md)
- [ADR-038: steering をナビゲーションに限定し、規約の正文を docs/ に置く](ADR-038-route-steering-to-docs-knowledge.md)
- [Matt Pocock skills](https://github.com/mattpocock/skills/tree/d81f3a183412e71a5b1e84ca21bc1a35eea03a60)
- [ADR-082: retro を導入し、タスクがひと段落したら必ず実行する](ADR-082-adopt-retro-and-run-it-at-task-boundaries.md)（`retro` と `writing-for-agents` を導入する）
- [ADR-084: pstack と thermos を導入し、RGDD の5つのフェーズで開発する](ADR-084-adopt-rgdd-with-pstack-and-thermos.md)（開発の流れと、外す Matt Pocock のスキル）
