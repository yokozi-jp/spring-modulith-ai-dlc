---
type: Convention
title: Pull Requestのタイトルと本文
description: Pull Requestのタイトルのtypeの選び方、本文に書く関連情報、画像とログのエビデンス、長い記録の折りたたみ、確認してほしい箇所と実装意図の書き分けを定める規約。Pull Requestを作成する、または本文を書くときに読む。
tags: [convention, code-review, pull-request, future-arch-guidelines]
---

# Pull Requestのタイトルと本文

タイトルはConventional Commits形式にし、squash commitのメッセージとして通用する要約にする。
本文には関連するIssueと資料へのリンクを貼り、Issueに書いた目的を繰り返さない。
画面の変更は画像で、ログとスタックトレースはテキストで示し、長い記録は折りたたむ。
実装の意図はPull Requestのコメントではなく、コードコメントか設計文書に書く。

## タイトル

タイトルの形式と使えるtypeは[コントリビューションガイド](../../CONTRIBUTING.md)のコミット規約に従い、CIの`Validate PR title`が検査する。
typeは[版の決まり方](../repository/release-management.md)に影響するため、複数のtypeに当てはまる場合は、`feat`、`fix`、その他の順で先にあるものを選ぶ。
Pull Requestにラベルを付ける場合も、typeはタイトルに書く。

## 本文

本文は[Pull Requestテンプレート](../../.github/PULL_REQUEST_TEMPLATE.md)の各節を埋める。
関連するIssueは`Closes #番号`のように本文から参照し、設計資料、ADR、議論の場所へのリンクも貼る。
作業の目的がIssueに書かれている場合は、本文に繰り返さず、要約を一文書くだけにする。
見出し、コードブロック、リストを使い、レビュアーが読む箇所を探せるようにする。

長い動作確認の記録やログは、`<details>`要素で折りたたむ。
GitHubの入力欄では、`/`で開くスラッシュコマンドの「Details」で挿入できる。

## エビデンス

- **画面の変更**：変更後の画面の画像を貼る。操作の流れを示す必要があれば動画にする。
- **スタックトレースとログ**：検索できるように、画像ではなくテキストで貼る。

画像とログには、秘密情報、実資格情報、個人情報を含めない。
ログに含めてはならない値は[可観測性データの規約](../observability/conventions.md)に従う。

## 確認してほしい箇所と実装の意図

特に念入りに確認してほしい箇所には、Files changedでセルフコメントを付ける。
実装の意図や背景は、セルフコメントではなく、コードコメントか設計文書に書く。
規約から意図して外れる実装は、その理由をコードコメントに書く。
レビュアーに意図を強調したい場合は、セルフコメントでそのコードコメントを指す。

## 出典

- フューチャー株式会社「コードレビューガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forCodeReview/code_review.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
