---
type: Convention
title: Pull Requestのタイトル、セルフコメント、チェックリスト
description: Pull Requestのタイトルのtypeの選び方、本文の三つの節（Summary、Evidence、Merge Danger）の書き方、セルフコメントとコードコメントの書き分け、テンプレートのチェックリストの項目の決め方を定める規約。Pull Requestのタイトルを決めるとき、本文を書くとき、セルフコメントを書くとき、テンプレートのチェックリストを変更するときに読む。
tags: [convention, pull-request, future-arch-guidelines]
---

# Pull Requestのタイトル、セルフコメント、チェックリスト

Pull Requestの本文は[Pull Requestテンプレート](../../.github/PULL_REQUEST_TEMPLATE.md)の三つの節（Summary、Evidence、Merge Danger）で書く。
タイトルはConventional Commits形式にし、複数のtypeに当てはまる場合は`feat`、`fix`、その他の順で選ぶ。
実装の意図はセルフコメントではなく、コードコメントか設計文書に書く。
テンプレートのチェックリストは、レビュー依頼前のセルフチェックに必要な最小限の項目にする。

## タイトル

タイトルの形式と使えるtypeは[コントリビューションガイド](../../CONTRIBUTING.md)のコミット規約に従い、CIの`Validate PR title`が検査する。
typeは[版の決まり方](../repository/release-management.md)に影響するため、複数のtypeに当てはまる場合は、`feat`、`fix`、その他の順で先にあるものを選ぶ。
Pull Requestにラベルを付ける場合も、typeはタイトルに書く。

## 本文の節

本文は squash commit の本文になる。
使わない節とコメントは消してから出す。

**Summary**：変更の要点を、図、diff、ツリー、疑似コードのうち一番小さいもので示す。
文章は短くする。

**Evidence**：動くことの証拠を、Before と After に分けて示す。
画面の変更は画像を貼り、操作の流れを示す必要があれば動画にする。
それ以外はテストやコマンドの出力を、検索できるように画像ではなくテキストで貼る。
長い記録は`<details>`で折りたたむ。
秘密情報、実資格情報、個人情報を含めない。
ログに含めてはならない値は[ログの規約](../observability/conventions.md)に従う。

**Merge Danger**：Door と Blast Radius を書く。
Door は、変更を戻せるかを示す。
簡単に戻せる変更は two-way、戻しにくい変更は one-way とする。
one-way は、[人が必ず読むPull Request](review-request-and-merge.md#人が必ず読むpull-request)の最初の条件（データの移行、公開しているAPIの変更、削除）と同じ範囲である。
Blast Radius は、影響範囲を一語で書く。
例は「api のみ」「web の表示」「全体の CI」である。

関連する Issue は、Merge Danger の後ろに`Closes #番号`で書く。

## セルフコメントとコードコメント

実装の意図や背景は、Pull Requestのセルフコメントではなく、コードコメントか設計文書に書く。
規約から意図して外れる実装は、その理由をコードコメントに書く。
レビュアーに意図を強調したい場合は、セルフコメントでそのコードコメントを指す。

## チェックリストの項目

[Pull Requestテンプレート](../../.github/PULL_REQUEST_TEMPLATE.md)のチェックリストは、Merge Danger の中に置き、API を変える変更のセルフチェックに使う。
項目は「OpenApiConfigの契約の版を上げたか」のように確認できる具体的な行動にし、必要最小限に保つ。
CIで検査できる項目はチェックリストへ加えず、検査へ移す。
確認されなくなった項目は削除する。

## 出典

- フューチャー株式会社「コードレビューガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forCodeReview/code_review.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
