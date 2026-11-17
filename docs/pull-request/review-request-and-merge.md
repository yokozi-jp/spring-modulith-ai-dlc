---
type: Convention
title: レビュー依頼からマージまで
description: Draft Pull Requestの使い方、Ready for reviewにする前の確認、人が読むPull Requestの扱い、マージする人を定める規約。Pull Requestを作成してからマージするまでの間に読む。
tags: [convention, pull-request, github, future-arch-guidelines]
---

# レビュー依頼からマージまで

作業途中のPull RequestはDraftで早めに作り、CIの成功と差分の確認を終えてからReady for reviewにする。
承認の数は0なので、人が必ず読む条件に当たるPull Requestだけ、マージの前に人が差分を読む。
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

## 人が読むPull Request

[人が必ず読む条件](../code-review/human-review-required.md)に当たるPull Requestは、マージの前に管理者が差分を読む。
当たらないPull Requestは、必須チェックが通っていれば、変更概要と「CI以外で確認したこと」を確かめてマージしてよい。

AssigneesとReviewersは設定しない。
CODEOWNERSの対象ファイルを変更した場合は、GitHubが所有者へレビューを自動で依頼する。
承認は要求されないため、レビューを依頼したときも、承認を待たずにマージの判断をしてよい。

## マージ

必須チェックが通ったら、作成者がマージする。
マージの方法と条件は[mainブランチの保護設定](../repository/branch-protection.md)に従う。
管理者がbypassでマージしたときは、理由をPull Requestに残す。

## 出典

- フューチャー株式会社「コードレビューガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forCodeReview/code_review.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
