# リポジトリのエージェント設定

## 学びの還元

作業で得た学びは、その作業の中で仕組みへ還元し、同じ誤りを次に起こさないようにする。
還元先はアーキテクチャ、型と静的検査、振る舞いのテスト、エージェント向けのdocs、人のレビューの順に検討し、上の層を優先する。
不具合を直したとき、レビューで指摘を受けたとき、エージェントが誤った変更をしたときは、`docs/principles/continuous-reinforcement.md` を読む。
タスクがひと段落したら（`/thermos` の指摘を直し終えた、Pull Request を作成した、利用者が作業の区切りを示した、のいずれかが最初に起きたとき）、セッションを消去する前に必ず `/retro` を実行する。
還元する候補の扱いは `docs/agents/rgdd.md` の「振り返り」にある。

## Agent skills

機能を作り始める前と、外部から入れたスキルの記述が docs の規約や ADR と食い違うときは、`docs/agents/rgdd.md` を読む。
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
pushはその文書のとおりbackgroundで実行する。
Pull RequestはDraftで作り、Ready for reviewにする前に`/thermos`を実行して指摘を直す（`docs/agents/rgdd.md`のフェーズ4）。
git worktreeを作ったら、同じ文書の「git worktreeで作業するとき」に従って依存を入れる。

### Commit messages

コミットする前に `docs/repository/commit-messages.md` を読む。
subjectは小文字始まりにし、末尾にピリオドを置かない。検査の正本は `commitlint.config.mjs` である。
