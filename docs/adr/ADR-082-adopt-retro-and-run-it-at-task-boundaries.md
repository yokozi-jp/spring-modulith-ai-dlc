---
type: ADR
title: 'ADR-082: retro を導入し、タスクがひと段落したら必ず実行する'
description: Matt Pocock の retro とその依存の writing-for-agents を固定リビジョンから導入し、タスクの区切りで retro を実行して環境の改善候補を得る決定。
tags: [adr, workflow, agent-skills, matt-pocock, continuous-improvement]
---

# ADR-082: retro を導入し、タスクがひと段落したら必ず実行する

## Status

Proposed

## Date

2026-10-21

## Context

[ADR-071](ADR-071-reinforce-learnings-into-five-layers.md) は、作業で得た学びを5つの層へ還元すると定めた。
しかし、還元の契機を見つけて実行するのはエージェントの判断に任されており、作業の区切りに振り返りを行う手順はなかった。

[ADR-039](ADR-039-adopt-matt-pocock-skills-workflow.md) は Matt Pocock のスキル群を `d81f3a183412e71a5b1e84ca21bc1a35eea03a60` に固定して導入したが、メインフローの最後の工程である `retro` は導入していない。
固定コミットには `retro` があり、`retro` は手順の最初に `writing-for-agents` を呼び出す。
`retro` は `disable-model-invocation: true` の利用者呼び出しのスキルであるため、エージェントが自ら実行する根拠を別に置かなければ呼び出されない。

上流は、うまく進んだセッションでの `retro` は一般論を生みやすいため、毎回の実行を勧めていない。
利用者は、このリポジトリではタスクの区切りごとに実行する運用を求めた。

## Decision

`retro` と、その依存である `writing-for-agents` を、ADR-039 と同じ固定コミットから導入する。
取得は `skills` CLI 1.7.0 で行い、実体を `.agents/skills/`、Kiro の検出用リンクを `.kiro/skills/`、内容ハッシュを `skills-lock.json` に置き、上流のファイルは変更しない。

タスクがひと段落したら、対象のセッションを消去する前に `/retro` を実行する。
この規則は `AGENTS.md` の「学びの還元」に置き、手順と読み替えは [Matt Pocock スキル運用](../agents/matt-pocock-skills/workflow.md)の「振り返り」を正文とする。
`/retro` は改善候補を提示するだけで、利用者が選んだ候補だけを ADR-071 の層の優先順位に従って還元する。

上流が `CODING_STANDARDS.md` に置く判断を要する規約は、このリポジトリでは該当する領域の docs に置く。

## Consequences

### Positive

- 学びの還元が、不具合やレビューの指摘を契機にした場合だけでなく、作業の区切りでも行われる。
- セッションの出来事に基づく候補を、深刻度順に受け取れる。
- 機械的な誤りを決定的な検査へ、判断を要する事項を docs へ振り分ける基準が、上流の手順として加わる。

### Negative

- 区切りごとに実行するため、トークンと利用者の確認の時間が増える。
- 上流が警告するとおり、うまく進んだセッションでは候補が一般論になりやすい。利用者は、セッションの具体的な出来事に結び付かない候補を採用しない。
- 導入するスキルが22になり、description の読み込み量と上流更新の確認範囲が広がる。

### Neutral

- `writing-for-agents` は英語の文書を前提にしており、日本語の書き方は `docs/writing/` に従う。
- 実行の頻度が過剰と分かった場合は、`AGENTS.md` の規則を見直す。

## Alternatives Considered

### retro を導入せず、既存の還元規約だけで運用する

- **Description**：ADR-071 の契機の列挙に従い、エージェントが個別に判断する。
- **Pros**：スキルを増やさない。
- **Cons**：作業の区切りで振り返る手順がなく、気付かなかった学びは仕組みに戻らない。

### 利用者が必要と感じたときだけ retro を実行する

- **Description**：上流の推奨どおり、利用者が手動で実行する。
- **Pros**：実行が過剰にならず、一般論の候補を避けやすい。
- **Cons**：利用者が実行を忘れると、学びが残らない。

## References

- [Matt Pocock スキル運用](../agents/matt-pocock-skills/workflow.md)
- [学びを仕組みへ還元し続ける](../principles/continuous-reinforcement.md)
- [retro リファレンス](../agents/matt-pocock-skills/retro.md)
- [writing-for-agents リファレンス](../agents/matt-pocock-skills/writing-for-agents.md)
