---
type: Convention
title: レビュー依頼からマージまで
description: Draft Pull Requestの使い方、レビュー依頼前の確認、AssigneesとReviewersの設定、レビューの依頼と再依頼、マージする人と承認後の変更の扱いを定める規約。Pull Requestを作成してからマージするまでの間に読む。
tags: [convention, pull-request, github, future-arch-guidelines]
---

# レビュー依頼からマージまで

作業途中のPull RequestはDraftで早めに作り、CIの成功と差分の確認を終えてからレビューを依頼する。
Assigneesは作成者が持ち、マージかクローズかが決まるまで推進する。
マージは原則としてレビュイーが行い、承認後に変更を加えた場合は再承認を受ける。

## Draft Pull Request

作業途中のPull Requestは、GitHubのDraftとして作る。
ローカルでの作業が終わる前でも、作業を見えるようにするためにDraftを早めに作ってよい。
Draftのままではレビューを依頼せず、レビューを受けられる状態になったらReady for reviewにする。

## レビュー依頼前の確認

レビューを依頼する前に、次を確認する。

1. 必須チェックを含むCIが成功している。
2. Files changedで、意図しないファイルや、誤った分岐元による余計な差分が含まれていない。
3. [Pull Requestテンプレート](../../.github/PULL_REQUEST_TEMPLATE.md)のチェックリストを確認した。

Files changedは、レビュアーの立場で差分を読み直すセルフレビューにも使う。
必須チェックの一覧とマージ条件は[mainブランチの保護設定](../repository/branch-protection.md)を参照する。

## AssigneesとReviewers

- **Assignees**：作成者自身を設定する。別の担当者へ引き継ぐときはAssigneesも変更する。Assigneesに設定された人が、マージかクローズかが決まるまで推進する。
- **Reviewers**：レビュアーが決まっている場合は、依頼時に必要な人数を設定する。決まっていない場合は空にし、候補者を片端から設定しない。
- **コード所有者**：CODEOWNERSの対象ファイルを変更した場合は、GitHubが所有者へレビューを依頼する。

## レビューの依頼と再依頼

レビューの依頼には、番号やタイトルだけでなくPull RequestのURLを添え、依頼する相手をメンションで明示する。
チームへ依頼する場合は、チームのメンバーだけを含むグループへメンションする。

指摘への対応を終えて再確認が必要な場合は、GitHubの再レビュー依頼かメンションで依頼する。

## マージ

承認と必須チェックがそろったら、レビュイーがマージする。
レビュアーの関与を増やす必要がある場合は、レビュアーがマージする。
typoの修正のような軽微な変更は、レビュアーが承認してそのままマージしてよい。

承認後にcommitを追加すると承認が取り消されるため、承認後に指摘を反映した場合は再承認を依頼する。
マージの方法と条件は[mainブランチの保護設定](../repository/branch-protection.md)に従う。

## 出典

- フューチャー株式会社「コードレビューガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forCodeReview/code_review.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
