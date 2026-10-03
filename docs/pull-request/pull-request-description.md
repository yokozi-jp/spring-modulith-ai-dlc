---
type: Convention
title: Pull Requestのタイトル、セルフコメント、チェックリスト
description: Pull Requestのタイトルのtypeの選び方、セルフコメントとコードコメントの書き分け、テンプレートのチェックリストの項目の決め方を定める規約。本文の節とエビデンスはテンプレートを正とする。Pull Requestのタイトルを決めるとき、セルフコメントを書くとき、テンプレートのチェックリストを変更するときに読む。
tags: [convention, pull-request, future-arch-guidelines]
---

# Pull Requestのタイトル、セルフコメント、チェックリスト

Pull Requestの本文は[Pull Requestテンプレート](../../.github/PULL_REQUEST_TEMPLATE.md)に従い、本文の節とエビデンスの書き方はテンプレートを正とする。
タイトルはConventional Commits形式にし、複数のtypeに当てはまる場合は`feat`、`fix`、その他の順で選ぶ。
実装の意図はセルフコメントではなく、コードコメントか設計文書に書く。
テンプレートのチェックリストは、レビュー依頼前のセルフチェックに必要な最小限の項目にする。

## タイトル

タイトルの形式と使えるtypeは[コントリビューションガイド](../../CONTRIBUTING.md)のコミット規約に従い、CIの`Validate PR title`が検査する。
typeは[版の決まり方](../repository/release-management.md)に影響するため、複数のtypeに当てはまる場合は、`feat`、`fix`、その他の順で先にあるものを選ぶ。
Pull Requestにラベルを付ける場合も、typeはタイトルに書く。

## セルフコメントとコードコメント

実装の意図や背景は、Pull Requestのセルフコメントではなく、コードコメントか設計文書に書く。
規約から意図して外れる実装は、その理由をコードコメントに書く。
レビュアーに意図を強調したい場合は、セルフコメントでそのコードコメントを指す。

## チェックリストの項目

[Pull Requestテンプレート](../../.github/PULL_REQUEST_TEMPLATE.md)のチェックリストは、レビュー依頼前のセルフチェックに使う。
項目は「関連文書も更新したか」のように確認できる具体的な行動にし、必要最小限に保つ。
CIで検査できる項目はチェックリストへ加えず、検査へ移す。
確認されなくなった項目は削除する。

## 出典

- フューチャー株式会社「コードレビューガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forCodeReview/code_review.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
