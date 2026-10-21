---
type: Runbook
title: Matt Pocock スキル運用
description: Matt Pocock のスキルを通常開発と例外的な作業で使い分け、コンテキストを工程間で引き継ぐ手順。
tags: [runbook, workflow, agent-skills, matt-pocock]
---

# Matt Pocock スキル運用

## 要約

- 通常の変更は `/grill-with-docs`、`/to-spec`、`/to-tickets`、`/implement` の順で進める。
- `/grill-with-docs` から `/to-tickets` までは同じセッションを維持し、チケットごとの `/implement` は新しいセッションで実行する。
- タスクがひと段落したら、セッションを消去する前に `/retro` を実行し、エージェントの環境の改善候補を受け取る。
- 実装前の対話だけでは決められない設計は、`/handoff` と `/prototype` を使って別のセッションで検証する。
- 通常フローに当てはまらない作業は `/ask-matt` に入口を選ばせる。
- プロジェクトの docs と ADR の規約は、外部スキル内の記述より優先する。

## 初回設定

`/setup-matt-pocock-skills` は、Issue tracker、triage label、ドメイン文書の配置を設定するときに一度だけ使う。
実行前に提案される変更を確認し、このリポジトリの `docs/`、ADR、文書ナビゲーションの規約を維持する。
上流スキルが `AGENTS.md`、`CLAUDE.md`、独自の ADR 書式を提案しても、自動的には採用しない。

## 通常の開発フロー

```text
idea
  ↓
/grill-with-docs
  ↓
必要なら /handoff → /prototype → /handoff
  ↓
/to-spec
  ↓
/to-tickets
  ↓
チケットごとに新しいセッションで /implement
  ├─ /tdd
  └─ /code-review
  ↓
/retro（同じセッションで、消去する前に）
  ↓
ship
```

まず、コードベースに対する変更は `/grill-with-docs` から始める。
このスキルは `/grilling` で未決事項を質問し、`/domain-modeling` で確定した用語と設計判断を記録する。
作業ディレクトリがなく、記録を残さない対話だけを行う場合は `/grill-me` を使う。

次に、対話だけでは判断できない状態モデル、業務ロジック、画面設計は `/prototype` で検証する。
元のセッションから `/handoff` を作り、別のセッションと作業場所でプロトタイプを実行し、結果をもう一度 `/handoff` で元の設計へ戻す。
プロトタイプは一つの設計上の疑問へ答えるために作り、製品コードとして扱わない。

方針が固まったら `/to-spec` で現在の対話を仕様へ変換し、`/to-tickets` で依存関係を持つ実装チケットへ分割する。
`/grill-with-docs` から `/to-tickets` までは同じセッションで続け、対話で決めた前提を仕様とチケットへ引き継ぐ。
コンテキストがモデルの処理能力を超えそうな場合だけ、工程の境界で圧縮して続ける。

実装では、チケットごとに新しいセッションを開始して `/implement` を実行する。
`/implement` は `/tdd` で小さな red、green、refactor の単位を進め、最後に `/code-review` でプロジェクト規約と仕様への適合を別々に確認する。
チケットは単独で実装できる情報を持たせ、前のチケットの会話へ依存させない。

## 振り返り

タスクがひと段落したら、対象のセッションを消去する前に、同じセッションで `/retro` を実行する。
ひと段落とは、`/implement` が `/code-review` まで終えたとき、または利用者が作業の区切りを示したときである。
消去してしまった場合は、そのセッションのログを指定して実行する。
`/retro` は `disable-model-invocation: true` の利用者呼び出しのスキルであるため、エージェントが実行する根拠は `AGENTS.md` の「学びの還元」に置く。

`/retro` は改善候補を提示するだけで、何も変更しない。
利用者が選んだ候補だけを、[学びを仕組みへ還元し続ける](../../principles/continuous-reinforcement.md)の5つの層の優先順位に従って還元する。
次の読み替えで、上流の記述をこのリポジトリに合わせる。

- 上流が `CODING_STANDARDS.md` に置く判断を要する規約は、該当する領域の docs に置き、`docs/code-review/review-viewpoints.md` から辿れるようにする。`CODING_STANDARDS.md` は作らない。
- 機械的な誤りは、`docs/tooling/lint-and-test.md` の既存の検査から探し、接続されていない検査を先に指摘する。
- steering と `AGENTS.md` は案内に限る。規約の本文は docs へ置く（[ADR-038](../../adr/ADR-038-route-steering-to-docs-knowledge.md)）。
- 文書を書く前に、`/writing-for-agents` の基準に加えて、`docs/knowledge/documentation-authoring.md` と `docs/knowledge/steering-authoring.md` を読む。
- 戻しにくい還元は、`docs/adr/conventions.md` の基準に当たれば ADR を起こす。

## 例外時の入口

- **入口に迷う場合**：`/ask-matt` に状況を渡し、使うスキル列を選ばせる。
- **難しい不具合**：`/diagnosing-bugs` で再現可能な確認を作り、原因を絞ってから修正する。
- **外部から来た Issue**：`/triage` で分類し、実装可能な説明へ整える。
- **一つのセッションに収まらない仕事**：`/wayfinder` で判断をチケットとして分け、見通しが立ったら `/to-spec` へ戻る。
- **コードベースの構造を改善する候補探し**：`/improve-codebase-architecture` で候補を調べ、`/codebase-design` で選んだ境界とインターフェースを検討する。
- **一次資料の調査**：`/research` で出典付きの調査文書を作り、その結果を `/grill-with-docs` へ渡す。
- **他の担当者だけが答えられる判断**：`/to-questionnaire` で質問票を作り、回答を `/grill-with-docs` または `/to-spec` へ渡す。
- **エージェント向け文書の執筆**：スキル、`AGENTS.md`、参照先 docs を書くときは `/writing-for-agents` を基準にし、日本語の書き方と docs の作成手順はプロジェクトの規約を優先する。
- **ドメイン用語と設計判断の整理**：`/domain-modeling` で Glossary と ADR を更新する。

## 文書の扱い

用語は現在の上流規約に合わせ、`CONTEXT.md` ではなく `GLOSSARY.md` に記録する。
複数のドメインで同じ語が異なる意味を持つ場合だけ、`GLOSSARY-MAP.md` とドメイン別の Glossary を検討する。
空の Glossary は作らず、最初のプロジェクト固有用語が確定した時点で作る。

ADR は `docs/adr/conventions.md` の条件、採番、書式に従う。
Matt Pocock の `ADR-FORMAT.md` が示す簡略書式は使わない。
`docs/` に新しい Markdown を作る場合は iwe を使い、該当する index からリンクする。

## 導入済みスキル

```text
ask-matt
code-review
codebase-design
diagnosing-bugs
domain-modeling
grill-me
grill-with-docs
grilling
handoff
implement
improve-codebase-architecture
prototype
research
retro
setup-matt-pocock-skills
tdd
to-questionnaire
to-spec
to-tickets
triage
wayfinder
writing-for-agents
```

## 取得元と更新

取得元は `mattpocock/skills` で、コミット `d81f3a183412e71a5b1e84ca21bc1a35eea03a60` に固定する。
導入には `skills` CLI 1.7.0 を使う。
内容ハッシュは `skills-lock.json` で管理し、上流の `SKILL.md` と同梱リソースは直接編集しない。

更新するときは新しいコミットを指定して取得し、現在の固定コミットとの差分を確認する。
`skills update` で無条件に最新版へ追従しない。
同梱スクリプト、発火条件、ファイル変更、Issue tracker 操作、コミット操作の変更を確認してからロックを更新する。

## 検証

`skills` CLI 1.7.0 の `list --json` で、固定コミットから導入した22スキルが Kiro CLI 用として認識されることを確認する。
各 `.agents/skills/<name>/SKILL.md` の `name` とディレクトリ名が一致し、`description` が存在することを確認する。
各 `.kiro/skills/<name>` が対応する `.agents/skills/<name>` への相対シンボリックリンクであることを確認する。
各ロックエントリの `ref` が固定コミットと一致することを確認する。

ローカルの `quick_validate.py` は、上流が使う `disable-model-invocation` と `argument-hint` を許可しないため、この外部スキル群の検証には使わない。
上流拡張フィールドを削除すると内容ハッシュと一致しなくなるため、スキルは変更せずに保持する。
