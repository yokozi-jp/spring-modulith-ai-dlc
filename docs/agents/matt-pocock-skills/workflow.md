---
type: Runbook
title: Matt Pocock スキル運用
description: RGDD のフェーズ2と Issue tracker の作業で使う Matt Pocock のスキルの扱い、振り返り、取得元と更新の手順。
tags: [runbook, workflow, agent-skills, matt-pocock]
---

# Matt Pocock スキル運用

## 要約

- 開発の全体の流れは [RGDD の開発フロー](../rgdd.md) に従う。この文書は、残している Matt Pocock のスキルの扱いだけを定める。
- `/grill-with-docs` から `/to-tickets` までは同じセッションを維持する。
- タスクがひと段落したら、セッションを消去する前に `/retro` を実行し、エージェントの環境の改善候補を受け取る。
- プロジェクトの docs と ADR の規約は、外部スキル内の記述より優先する。
- 上流とは、スキルの取得元である `mattpocock/skills` リポジトリを指す。

## 初回設定

`/setup-matt-pocock-skills` は、Issue tracker、triage label、ドメイン文書の配置を設定するときに一度だけ使う。
実行前に提案される変更を確認し、このリポジトリの `docs/`、ADR、文書ナビゲーションの規約を維持する。
上流スキルが `AGENTS.md`、`CLAUDE.md`、独自の ADR 書式を提案しても、自動的には採用しない。

## 設計を詰める

`/grill-with-docs` は `/grilling` で未決事項を質問し、`/domain-modeling` で確定した用語と設計判断を記録する。
対話だけでは判断できない状態モデル、業務ロジック、画面設計は、`/prototype` で一つの問いに答える使い捨ての試作を作って確かめる。
試作は製品コードとして扱わない。

方針が固まったら `/to-spec` で現在の対話を仕様へ変換し、`/to-tickets` で依存関係を持つ実装チケットへ分割する。
`/grill-with-docs` から `/to-tickets` までは同じセッションで続け、対話で決めた前提を仕様とチケットへ引き継ぐ。
チケットは単独で実装できる情報を持たせ、前のチケットの会話へ依存させない。
実装は [RGDD の開発フロー](../rgdd.md)のフェーズ3から進める。

## 振り返り

タスクがひと段落したら、対象のセッションを消去する前に、同じセッションで `/retro` を実行する。
ひと段落とは、次のいずれかが最初に起きたときである。

- チケットの `/thermos` の指摘を直し終えた。
- Pull Request を作成した。
- 利用者が作業の区切りを示した。

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

- **外部から来た Issue**：`/triage` で分類し、実装可能な説明へ整える。
- **一つのセッションに収まらない仕事**：`/wayfinder` で判断をチケットとして分け、見通しが立ったら `/to-spec` へ戻る。
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

## 取得元と更新

取得元は `mattpocock/skills` で、コミット `d81f3a183412e71a5b1e84ca21bc1a35eea03a60` に固定する。
導入済みのスキルは `skills-lock.json` が正で、この文書には一覧を写さない。
導入には `skills` CLI 1.7.0 を使い、上流の `SKILL.md` と同梱リソースは直接編集しない。

導入するときは、固定コミットを指定して取得し、実体を `.agents/skills/`、Kiro の検出用リンクを `.kiro/skills/` に揃える。
`-a kiro-cli` を指定すると CLI は `.kiro/skills/` に実体をコピーするため、実体を `.agents/skills/` へ移して相対シンボリックリンクに直す。

```bash
npx skills@1.7.0 add "mattpocock/skills#d81f3a183412e71a5b1e84ca21bc1a35eea03a60" -s <スキル名> -a kiro-cli -y
mv .kiro/skills/<スキル名> .agents/skills/<スキル名>
ln -s ../../.agents/skills/<スキル名> .kiro/skills/<スキル名>
task skills-check
```

スキルが別のスキルを呼ぶ場合は、呼ばれるスキルも同じ手順で導入する。
`SKILL.md` の本文に、呼び出すスキルの名前があるかを確かめる。

更新するときは新しいコミットを指定して取得し、現在の固定コミットとの差分を確認する。
`skills update` で無条件に最新版へ追従しない。
同梱スクリプト、発火条件、ファイル変更、Issue tracker 操作、コミット操作の変更を確認してからロックを更新する。

## 検証

導入と更新のあとは `task skills-check` を実行する。
このタスクは、`skills-lock.json`、`.agents/skills/` の実体、`.kiro/skills/` のシンボリックリンクの整合を検査する。
pstack と thermos の検査は [RGDD の開発フロー](../rgdd.md)の「検証」にある。
`SKILL.md` の `name` がディレクトリ名と一致し、`description` があること、内容ハッシュが lock と一致すること、取得元ごとの `ref` が一つであることも確かめる。
検査の範囲は [Lint・テストのリファレンス](../../tooling/lint-and-test.md) にある。

ローカルの `quick_validate.py` は、上流が使う `disable-model-invocation` と `argument-hint` を許可しないため、この外部スキル群の検証には使わない。
上流拡張フィールドを削除すると内容ハッシュと一致しなくなるため、スキルは変更せずに保持する。
