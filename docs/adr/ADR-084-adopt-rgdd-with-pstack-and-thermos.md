---
type: ADR
title: 'ADR-084: pstack と thermos を導入し、RGDD の5つのフェーズで開発する'
description: pstack、thermos、cursor-team-kit の一部を cursor-plugins の kiro/install.sh で導入し、Matt Pocock の実装とレビューのスキルを外して、README から検証までの5つのフェーズで開発する決定。
tags: [adr, workflow, agent-skills, pstack, thermos, matt-pocock]
---

# ADR-084: pstack と thermos を導入し、RGDD の5つのフェーズで開発する

## Status

Proposed

## Date

2026-11-16

## Context

[ADR-039](ADR-039-adopt-matt-pocock-skills-workflow.md) は、Matt Pocock のスキルで `/grill-with-docs`、`/to-spec`、`/to-tickets`、`/implement`（内部で `/tdd` と `/code-review`）の順に開発すると定めた。

利用者は、実装、レビュー、検証を pstack と thermos に任せる進め方（README駆動 with Grill Driven Development、以下 RGDD）へ移ることを求めた。
RGDD は、README、`GLOSSARY.md`、ADR をコードより先に置き、次の5つのフェーズでそれらに書き足し、確かめて直し続ける。

1. 開発者が README に、ユーザーから見た使い方を書く。
2. `/grill-with-docs` で、ドメイン用語と ADR を作る。
3. `/poteto-mode` で開発する。
4. `/thermos` でレビューする。
5. `/create-verification-skill` で検証スキルと Feature Map を作り、`/maintain-verification-skill` で保つ。

pstack と thermos は Cursor 向けに書かれている。
利用者が保守する [yokozi-jp/cursor-plugins](https://github.com/yokozi-jp/cursor-plugins) は cursor/plugins のフォークで、上流のファイルを変えずに `kiro/` だけを足し、Kiro 向けのエージェント、Cursor の名前の読み替え、権限の雛形、導入スクリプトを持つ。

Matt Pocock のスキルのうち、実装とレビューを担うものは pstack と thermos の役割と重なる。
`/tdd` は同じ名前で役割が違い、両方は置けない。
一方、`grilling`、`grill-with-docs`、`domain-modeling` はフェーズ2に、Issue tracker と連携するスキルは pstack にない用途に使う。
`wayfinder` はチケットの解決に `research` と `prototype` を呼ぶ。
`retro` は [ADR-071](ADR-071-reinforce-learnings-into-five-layers.md) の5つの層へ環境を還元する候補を返し、pstack の `reflect` は改善の行き先をスキルに置くため、このリポジトリの還元先と合わない。

## Decision

pstack（`setup-pstack` を除く）、thermos、cursor-team-kit の `deslop`、`control-cli`、`control-ui` を、cursor-plugins の `kiro/install.sh --repo` で `.kiro/` にコピーして導入する。
導入したファイルとその内容ハッシュは `.kiro/.kiro-plugins-installed` に記録し、`task skills-check` が記録と比べる。
導入したファイルは編集せず、変更は cursor-plugins の `kiro/` か上流で行う。

開発は RGDD の5つのフェーズで進め、手順の正文は [RGDD の開発フロー](../agents/rgdd.md)に置く。

Matt Pocock のスキルから、`ask-matt`、`code-review`、`codebase-design`、`diagnosing-bugs`、`grill-me`、`handoff`、`implement`、`improve-codebase-architecture`、`using-git-worktrees`、`tdd` を外す。
`tdd` は pstack の同名のスキルに置き換える。
`grilling`、`grill-with-docs`、`domain-modeling`、`to-spec`、`to-tickets`、`triage`、`wayfinder`、`to-questionnaire`、`setup-matt-pocock-skills`、`research`、`prototype`、`retro`、`writing-for-agents`、`skill-creator` は ADR-039 と同じ方法と固定コミットで残す。

[ADR-082](ADR-082-adopt-retro-and-run-it-at-task-boundaries.md) の、タスクの区切りで `/retro` を実行する規則は残し、区切りの例から `/implement` の完了を外す。

この ADR は ADR-039 を置き換える。

## Consequences

### Positive

- 実装、レビュー、検証を、pstack のプレイブック、thermos の2つの監査、プロジェクト専用の検証スキルで行える。
- pstack と thermos の更新は、cursor-plugins で上流と同期して `kiro/install.sh --repo` を再実行するだけで取り込める。
- 導入したファイルの編集を、`skills-lock.json` で管理するスキルと同じく内容ハッシュで検出できる。

### Negative

- スキルの取得元が2つになり、`skills-lock.json` と `.kiro/.kiro-plugins-installed` の2つの記録を読む必要がある。
- pstack と thermos のエージェントは `claude-opus-5.5` に固定され、呼び出しごとのクレジットが増える。
- Kiro には呼び出しごとにモデルを選ぶ手段がないため、pstack の複数モデルのパネルは1つのモデルで動く。
- `code-review` が持っていた、このリポジトリの規約に照らすレビューは、steering `code-review` と `docs/code-review/` が担う。

### Neutral

- 外したスキルの日本語リファレンスは削除し、上流と git の履歴に残す。
- `skills-lock.json` の項目と、`.kiro/.kiro-plugins-installed` の項目は重ならない。重なると `task skills-check` が失敗にする。

## Alternatives Considered

### Matt Pocock のフローを続け、pstack と thermos を足す

- **Description**：`/implement`、`/tdd`、`/code-review` を残したまま、pstack と thermos を加える。
- **Pros**：既存の手順を変えない。
- **Cons**：実装とレビューの入口が二重になり、同名の `tdd` は片方しか置けない。

### pstack の `reflect` で振り返る

- **Description**：`/retro` を外し、`/reflect` を区切りごとに実行する。
- **Pros**：スキルを pstack に寄せられる。
- **Cons**：`reflect` は採用した学びをスキルの編集として還元し、導入したスキルは編集できない。サブエージェントを4回起動するため、区切りごとの実行には重い。

### `.agents/skills/` の実体と `skills-lock.json` で pstack と thermos も管理する

- **Description**：ADR-082 の導入方法に合わせ、全スキルを `skills` CLI で取得する。
- **Pros**：記録が1つで済む。
- **Cons**：Kiro 向けのエージェントと読み替えを `skills` CLI では配れず、cursor-plugins の導入スクリプトと二重に管理することになる。

## References

- [RGDD の開発フロー](../agents/rgdd.md)
- [Matt Pocock スキル運用](../agents/matt-pocock-skills/workflow.md)
- [yokozi-jp/cursor-plugins の kiro/README.ja.md](https://github.com/yokozi-jp/cursor-plugins/blob/main/kiro/README.ja.md)
- [cursor/plugins の pstack](https://github.com/cursor/plugins/tree/main/pstack)
- [cursor/plugins の thermos](https://github.com/cursor/plugins/tree/main/thermos)
