# リポジトリのエージェント設定

## 学びの還元

作業で得た学びは、その作業の中で仕組みへ還元し、同じ誤りを次に起こさないようにする。
還元先はアーキテクチャ、型と静的検査、振る舞いのテスト、エージェント向けのdocs、人のレビューの順に検討し、上の層を優先する。
不具合を直したとき、レビューで指摘を受けたとき、エージェントが誤った変更をしたときは、`docs/principles/continuous-reinforcement.md` を読む。

## Agent skills

### Matt Pocock skills

Matt Pocock のスキルを使う前に `docs/agents/matt-pocock-skills/workflow.md` を読む。
スキルの手順とプロジェクトの規約が食い違うときは、docs の規約と ADR を優先する。

### Issue tracker

Issueと仕様はGitHub Issuesで管理する。詳細は `docs/agents/issue-tracker.md` を参照する。

### Triage labels

Triageでは5種類の標準ラベルを使う。詳細は `docs/agents/triage-labels.md` を参照する。

### Domain docs

ドメイン文書はsingle-context構成で管理する。詳細は `docs/agents/domain.md` を参照する。

## Git

### Commit messages

コミットする前に `docs/repository/commit-messages.md` を読む。
subjectは小文字始まりにし、末尾にピリオドを置かない。検査の正本は `commitlint.config.mjs` である。
