---
type: Runbook
title: Matt Pocock スキル運用
description: RGDD のフェーズ2に乗らない作業で Matt Pocock のスキルを使う入口と、初回設定、文書の扱い、使わない検証スクリプトを定める Runbook。Matt Pocock のスキルを使う前に読む。
tags: [runbook, workflow, agent-skills, matt-pocock]
---

# Matt Pocock スキル運用

## 要約

- この文書は、残している Matt Pocock のスキルに固有の扱いだけを定める。開発の流れ、同じセッションを保つ範囲、振り返りは [RGDD の開発フロー](../rgdd.md) にある。
- 用語は `GLOSSARY.md` に記録し、ADR は `docs/adr/conventions.md` の書式で書く。
- 上流とは、スキルの取得元である `mattpocock/skills` リポジトリを指す。取得元と更新の手順は [スキルの取得元と更新](../skill-sources.md) にある。

## 初回設定

`/setup-matt-pocock-skills` は、Issue tracker、triage label、ドメイン文書の配置を設定するときに一度だけ使う。
実行前に提案される変更を確認し、このリポジトリの `docs/`、ADR、文書ナビゲーションの規約を維持する。
上流スキルが `AGENTS.md`、`CLAUDE.md`、独自の ADR 書式を提案しても、自動的には採用しない。

## 例外時の入口

- **外部から来た Issue**：`/triage` で分類し、実装可能な説明へ整える。
- **一つのセッションに収まらない仕事**：`/wayfinder` で判断をチケットとして分け、見通しが立ったら `/to-spec` へ戻る。
- **一次資料の調査**：`/research` で出典付きの調査文書を作り、その結果を `/grill-with-docs` へ渡す。
- **対話だけでは判断できない状態モデル、業務ロジック、画面設計**：`/prototype` で一つの問いに答える使い捨ての試作を作って確かめる。試作は製品コードとして扱わない。
- **コードベースの構造を改善する候補探し**：`/improve-codebase-architecture` で深くする候補を HTML レポートにし、選んだ候補のインターフェースを `/codebase-design` の語彙と手順で検討する。
- **他の担当者だけが答えられる判断**：`/to-questionnaire` で質問票を作り、回答を `/grill-with-docs` または `/to-spec` へ渡す。
- **エージェント向け文書の執筆**：スキル、`AGENTS.md`、参照先 docs を書くときは `/writing-for-agents` を基準にし、日本語の書き方は `docs/writing/` に従う。
- **ドメイン用語と設計判断の整理**：`/domain-modeling` で Glossary と ADR を更新する。

## 文書の扱い

用語は現在の上流規約に合わせ、`CONTEXT.md` ではなく `GLOSSARY.md` に記録する。
複数のドメインで同じ語が異なる意味を持つ場合だけ、`GLOSSARY-MAP.md` とドメイン別の Glossary を検討する。
空の Glossary は作らず、最初のプロジェクト固有用語が確定した時点で作る。

ADR は `docs/adr/conventions.md` の条件、採番、書式に従う。
Matt Pocock の `ADR-FORMAT.md` が示す簡略書式は使わない。
`docs/` に新しい Markdown を作る場合は iwe を使い、該当する index からリンクする。

## skill-creator の quick_validate.py

ローカルの `quick_validate.py` は、上流が使う `disable-model-invocation` と `argument-hint` を許可しないため、この外部スキル群の検証には使わない。
上流拡張フィールドを削除すると内容ハッシュと一致しなくなるため、スキルは変更せずに保持する。
