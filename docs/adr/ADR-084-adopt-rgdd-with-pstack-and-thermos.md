---
type: ADR
title: 'ADR-084: pstack と thermos を導入し、RGDD の5つのフェーズで開発する'
description: README から検証までの5つのフェーズ（RGDD）で開発し、pstack、thermos、cursor-team-kit の一部を cursor-plugins の kiro/install.sh で固定コミットから導入する決定。
tags: [adr, workflow, agent-skills, pstack, thermos, matt-pocock]
---

# ADR-084: pstack と thermos を導入し、RGDD の5つのフェーズで開発する

## Status

Proposed

## Date

2026-11-16

## Context

[ADR-039](ADR-039-adopt-matt-pocock-skills-workflow.md) は当初、Matt Pocock のスキルで `/grill-with-docs`、`/to-spec`、`/to-tickets`、`/implement`（内部で `/tdd` と `/code-review`）の順に開発すると定め、スキルを固定コミットから `skills` CLI で取得すると定めた。

利用者は、実装、レビュー、検証を pstack と thermos に任せる進め方へ移ることを求めた。
この進め方を README駆動 with Grill Driven Development（以下 RGDD）と呼ぶ。
RGDD は、README、`GLOSSARY.md`、ADR をコードより先に置き、各フェーズでそれらに書き足し、確かめて直し続ける。

pstack と thermos は Cursor 向けに書かれている。
利用者が保守する [yokozi-jp/cursor-plugins](https://github.com/yokozi-jp/cursor-plugins) は cursor/plugins のフォークで、上流のファイルを変えずに `kiro/` だけを足し、Kiro 向けのエージェント、Cursor の名前の読み替え、権限の雛形、導入スクリプトを持つ。

外部スキルはエージェントへ手順を与え、一部は同梱スクリプトを実行し、ファイルを書き、コミットや push を指示する。
ADR-039 が Matt Pocock のスキルで懸念したとおり、上流の更新をレビューせずに取り込むと、権限の境界やこのリポジトリの規約に反する手順が入る。

Matt Pocock のスキルのうち、実装とレビューを担うものは pstack と thermos の役割と重なる。
`/tdd` は同じ名前で役割が違い、両方は置けない。
一方、`grilling`、`grill-with-docs`、`domain-modeling` はフェーズ2で使う。
Issue tracker と連携するスキルと、既存のコードから深くするモジュールの候補を探す `improve-codebase-architecture`（と、それが呼ぶ `codebase-design`）は、pstack にない用途で使う。
`retro` は [ADR-071](ADR-071-reinforce-learnings-into-five-layers.md) の5つの層へ環境を還元する候補を返す。

Proposed の間に、Pull Request のレビュー（`/thermos`）の指摘を受けて、Decision を次の点で書き直した。
ADR-039 と [ADR-082](ADR-082-adopt-retro-and-run-it-at-task-boundaries.md) を置き換えず、どちらも Proposed であるため、それぞれの Decision を RGDD に合わせて書き直した。
cursor-plugins の取り込み元を固定コミットにし、更新のレビューを決定に加えた。
入れないスキルとその理由を決定に加えた。
取得元のないスキルの記録の規則を ADR-039 へ移した。
利用者の判断で、`improve-codebase-architecture` と `codebase-design` を外すスキルから戻した。

## Decision

開発は、次の RGDD の5つのフェーズで進める。
手順の正文は [RGDD の開発フロー](../agents/rgdd.md)に置く。

1. 開発者が README に、ユーザーから見た使い方を書く。
2. `/grill-with-docs` で、ドメイン用語と ADR を作る。
3. `/poteto-mode` で開発する。
4. `/thermos` でレビューする。
5. `/create-verification-skill` で検証スキルと Feature Map を作り、`/maintain-verification-skill` で保つ。

pstack、thermos、cursor-team-kit の `deslop`、`control-cli`、`control-ui` を、cursor-plugins の `kiro/install.sh --repo` で `.kiro/` にコピーして導入する。
pstack のうち、次のスキルは入れない。

- **`setup-pstack`**：モデルは cursor-plugins の `kiro/agents/` のエージェントファイルで決まり、設定する対象がない。
- **`make-bot-ui`**：Cursor の `update_state` ツールと Routines パネルを前提にし、手順に `curl ... | sudo sh` を含む。
- **`ketchup-video`**：動画を埋め込めるチャットを前提にする。
- **`reflect`**：採用した学びを入れたスキルの編集として還元し、記録した内容ハッシュと合わなくなる。改善の行き先も ADR-071 の5つの層と合わない。

入れないスキルは cursor-plugins の `kiro/install.sh` の `excluded_skills` に置き、`kiro/install.sh` は同じ名前が `kiro-adapter` の案内にあることを確かめる。

取り込み元は、`.kiro/.kiro-plugins-installed` の1行目に記録する cursor-plugins のコミットに固定する。
`kiro/install.sh` は、未コミットの変更がある checkout からは入れない。
更新は固定コミットを変える Pull Request で行い、旧と新のコミットの差分（同梱スクリプト、発火条件、権限、ファイルを書くコマンド）をレビューしてから入れ、Pull Request の説明に旧と新のコミットを書く。
入れたファイルとその内容ハッシュは `.kiro/.kiro-plugins-installed` に記録し、`task skills-check` が記録と比べる。
入れたファイルは編集せず、変更は cursor-plugins の `kiro/` か上流で行う。
手順の正文は [スキルの取得元と更新](../agents/skill-sources.md)に置く。

残す Matt Pocock のスキルとその取得は ADR-039、振り返りは ADR-082 に従う。

## Consequences

### Positive

- 実装、レビュー、検証を、pstack のプレイブック、thermos の2つの監査、プロジェクト専用の検証スキルで行える。
- マニフェストの1行目に取り込み元のコミットが残るため、入れた版を再現でき、更新の差分をコミットの間でレビューできる。
- 導入したファイルの編集を、`skills-lock.json` で管理するスキルと同じく内容ハッシュで検出できる。

### Negative

- スキルの取得元が2つになり、`skills-lock.json` と `.kiro/.kiro-plugins-installed` の2つの記録を読む必要がある。
- Kiro はスキルの名前と description をスキルの一覧として読み込むため、毎回のコンテキストが増える。
  cursor-plugins で入れる61スキルの description は合計12,491文字である。
  `skills-lock.json` の17スキルを合わせると78スキルで15,213文字になり、この変更の前（24スキル、3,879文字）の約3.9倍になる。
  文字数は、各 `SKILL.md` の frontmatter の `description` の値を数えた。
- 更新のたびに、旧と新のコミットの間の pstack、thermos、`kiro/` の差分を読む手間がかかる。
- pstack と thermos のエージェントは `claude-opus-5.5` に固定され、呼び出しごとのクレジットが増える。
- Kiro には呼び出しごとにモデルを選ぶ手段がないため、pstack の複数モデルのパネルは1つのモデルで動く。
- pstack のプレイブックには、このリポジトリの規約と食い違う指示（Pull Request を Draft にしない、`--force-with-lease` で push する）がある。
  RGDD の開発フローが規約を優先する箇所を示すが、上流の更新で新しい食い違いが入りうるため、更新のレビューで探す。
- 外す `code-review` が持っていた、このリポジトリの規約に照らすレビューは thermos の監査に含まれない。
  フェーズ4で `docs/code-review/` の観点に照らして見直す手順を置くが、エージェントがその手順を飛ばしても検出する仕組みはない。

### Neutral

- 実装とレビューの入口を pstack と thermos にそろえるため、役割が重なる Matt Pocock の `ask-matt`、`code-review`、`diagnosing-bugs`、`grill-me`、`handoff`、`implement`、`tdd` を外す。`using-git-worktrees` は便利なため残す。
  `tdd` は pstack の同名のスキルに置き換わる。
- 外したスキルの日本語リファレンスは削除し、上流と git の履歴に残す。
- `skills-lock.json` の項目と、`.kiro/.kiro-plugins-installed` の項目は重ならない。重なると `task skills-check` が失敗にする。
- `/maintain-verification-skill` を実行する頻度は、[#366](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/366) で決める。

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

### cursor-plugins の main に追従し、コミットを固定しない

- **Description**：更新のたびに cursor-plugins の main を取得して入れ直す。
- **Pros**：コミットを選ぶ手間がない。
- **Cons**：上流の変更がレビューなしで入り、マニフェストから入れた版を再現できない。

## References

- [RGDD の開発フロー](../agents/rgdd.md)
- [スキルの取得元と更新](../agents/skill-sources.md)
- [ADR-039: Matt Pocock のスキルフローを導入する](ADR-039-adopt-matt-pocock-skills-workflow.md)
- [ADR-082: retro を導入し、タスクがひと段落したら必ず実行する](ADR-082-adopt-retro-and-run-it-at-task-boundaries.md)
- [yokozi-jp/cursor-plugins の kiro/README.ja.md](https://github.com/yokozi-jp/cursor-plugins/blob/main/kiro/README.ja.md)
- [cursor/plugins の pstack](https://github.com/cursor/plugins/tree/main/pstack)
- [cursor/plugins の thermos](https://github.com/cursor/plugins/tree/main/thermos)
