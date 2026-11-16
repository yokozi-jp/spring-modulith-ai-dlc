# リポジトリのエージェント設定

## 学びの還元

作業で得た学びは、その作業の中で仕組みへ還元し、同じ誤りを次に起こさないようにする。
還元先はアーキテクチャ、型と静的検査、振る舞いのテスト、エージェント向けのdocs、人のレビューの順に検討し、上の層を優先する。
不具合を直したとき、レビューで指摘を受けたとき、エージェントが誤った変更をしたときは、`docs/principles/continuous-reinforcement.md` を読む。
タスクがひと段落したら（`/thermos` の指摘を直し終えた、Pull Request を作成した、利用者が作業の区切りを示した、のいずれかが最初に起きたとき）、セッションを消去する前に必ず `/retro` を実行する。
`/retro` は改善候補を提示するだけなので、還元するのは利用者が選んだ候補に限る（手順は `docs/agents/rgdd.md` の「振り返り」）。

## Agent skills

機能を作り始める前に `docs/agents/rgdd.md` を読む。
外部から入れたスキル（`skills-lock.json` と `.kiro/.kiro-plugins-installed` に載るもの）の記述と docs の規約や ADR が食い違うときは、docs の規約と ADR に従う。
スキルを導入、更新する前に `docs/agents/skill-sources.md` を読む。
`.kiro/.kiro-plugins-installed` に載るファイル（pstack と thermos）は編集しない。

### Matt Pocock skills

Matt Pocock のスキルを使う前に `docs/agents/matt-pocock-skills/workflow.md` を読む。

### Issue tracker

Issueと仕様はGitHub Issuesで管理する。詳細は `docs/agents/issue-tracker.md` を参照する。

### Triage labels

Triageでは5種類の標準ラベルを使う。詳細は `docs/agents/triage-labels.md` を参照する。

### Domain docs

ドメイン文書はsingle-context構成で管理する。詳細は `docs/agents/domain.md` を参照する。

## Git

commitとpushの前に、`docs/tooling/dev-workflow.md`の「エージェントがcommitとpushをするとき」を読む。
git worktreeを作ったら、同じ文書の「git worktreeで作業するとき」に従って依存を入れる。

### Commit messages

コミットする前に `docs/repository/commit-messages.md` を読む。
subjectは小文字始まりにし、末尾にピリオドを置かない。検査の正本は `commitlint.config.mjs` である。
