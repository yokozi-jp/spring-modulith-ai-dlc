# リポジトリのエージェント設定

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
