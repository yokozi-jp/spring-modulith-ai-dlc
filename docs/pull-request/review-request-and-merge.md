---
type: Convention
title: Draftからマージまで
description: Draft Pull Requestの使い方、Ready for reviewにする前の確認、人が必ず読むPull Requestの条件、マージする人を定める規約。Pull Requestを作成してからマージするまでの間に読む。
tags: [convention, pull-request, github, future-arch-guidelines]
---

# Draftからマージまで

作業途中のPull RequestはDraftで早めに作り、CIの成功と差分の確認を終えてからReady for reviewにする。
人が必ず読む条件に当たるPull Requestだけ、マージの前に人が差分を読む。
マージは作成者が、必須チェックが通ってから行う。

## Draft Pull Request

作業途中のPull Requestは、GitHubのDraftとして作る。
ローカルでの作業が終わる前でも、作業を見えるようにするためにDraftを早めに作ってよい。
Draftのままではマージせず、CIと差分の確認を終えたらReady for reviewにする。

## Ready for reviewにする前の確認

Ready for reviewにする前に、次を確認する。

1. 必須チェックを含むCIが成功している。
2. Files changedで、意図しないファイルや、誤った分岐元による余計な差分が含まれていない。
3. [Pull Requestテンプレート](../../.github/PULL_REQUEST_TEMPLATE.md)のチェックリストを確認した。

Files changedは、レビュアーの立場で差分を読み直すセルフレビューにも使う。
必須チェックの一覧とマージ条件は[mainブランチの保護設定](../repository/branch-protection.md)を参照する。

CIの完了は、必須チェックだけに絞って待つ。

```bash
gh pr checks <番号> --required --watch --fail-fast
```

`--required`を付けないと、必須でないE2EやDASTの完了まで待つ。
エージェントは、このコマンドを長時間の処理としてbackgroundで実行する。
Snyk Codeは必須チェックではないため待たない。

## 人が必ず読むPull Request

次のどれかに当たるPull Requestは、マージの前に人が差分を読む。
当たるか迷うときは、当たるものとして扱う。

- 戻しにくい変更を含む。データの移行、公開しているAPIの変更、削除が該当する。
- ADR、`AGENTS.md`、`docs/agents/`、`.kiro/`（steering、skill、hook、settings）、`.agents/`を変える。
- CIとhookのゲートを変える。`.github/`（workflow、CODEOWNERS、`dependabot.yml`）、`lefthook.yml`、`commitlint.config.mjs`、ruleset、検査の設定と除外（`.betterleaks.toml`、`.snyk`、`docker/zap/`、PMD、SpotBugs、Semgrepのルール）、`Taskfile.yml`の検査Taskが該当する。
- 認証、認可、CSRF、秘密情報の扱いを変える。
- 依存を追加する。
- テストを弱める。テストファイルを消す、テストケースを減らす、coverageやmutationの基準を下げる、検査をskipするなどが該当する。

Dependabotのpatchとminorの更新は、[ADR-021](../adr/ADR-021-group-dependabot-minor-and-patch-updates.md)に従って自動でマージされるため、この条件の対象にしない。

当たらないPull Requestは、必須チェックが通っていれば、変更概要と「CI以外で確認したこと」を確かめてマージしてよい。
承認は要求されない（理由は [ADR-086](../adr/ADR-086-align-main-protection-with-solo-and-agent-operation.md)）。

エージェントは、条件に当たるPull Requestをマージしない。
`gh pr merge --admin`とauto-mergeの有効化は、人だけが行う。

## マージ

必須チェックが通ったら、作成者がマージする。
マージの方法と条件は[mainブランチの保護設定](../repository/branch-protection.md)に従う。

## 出典

- フューチャー株式会社「コードレビューガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forCodeReview/code_review.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
