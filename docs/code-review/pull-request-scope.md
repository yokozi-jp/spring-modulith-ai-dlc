---
type: Convention
title: Pull Requestの範囲と分割
description: 実装前に方針を合意する変更の条件、境界のスキーマを先にレビューする手順、Pull Requestの大きさと分割、差分の大きさと改善の優先順位、ファイルの移動やリネームと編集の分け方を定める規約。変更に着手する前、Pull Requestを分けるか迷ったときに読む。
tags: [convention, code-review, pull-request, future-arch-guidelines]
---

# Pull Requestの範囲と分割

方針に迷いが残る変更は、実装の前にIssueで方針を合意する。
Pull Requestは一つの目的に絞って小さくし、リファクタリングと機能変更を分ける。
差分を小さくするために必要な改善を見送らず、ファイルの移動やリネームは内容の編集と分ける。

## 実装前に方針を合意する変更

設計書やIssueに方針が書かれた新規機能と、原因と対応方針がIssueに書かれた不具合修正は、改めて方針を合意しなくてよい。
次の変更は、実装に入る前にIssueで方針を合意する。

- 稼働中の機能の挙動を変える変更。
- 原因が特定されていない不具合の修正。

不具合修正のIssueには、事象、業務影響、システム影響、原因分析、対応方針、影響範囲を書く。
既存機能の挙動を変えるIssueには、変更理由、対応方針、影響範囲を書く。
影響範囲には、Pull Requestやリリースを分けるかどうかを含める。

Issueの操作は[Issue tracker](../agents/issue-tracker.md)に従う。
設計判断を伴う変更は、[ADRの運用ルール](../adr/conventions.md)に従ってADRを起こす。

## 境界のスキーマを先にレビューする

API契約（OpenAPI）とDBスキーマ（Liquibaseのchangeset）の変更は、それを使うアプリケーションコードより先にDraft Pull Requestでレビューを受ける。
これらへの指摘は呼び出し側のコードの修正を伴い、後から受けると手戻りが大きくなる。

## Pull Requestの大きさ

Pull Requestは一つの目的に絞る。
変更したファイルが20を超える場合は、分割できないかを検討する。
機能変更の前にリファクタリングする場合は、リファクタリングだけのPull Requestと機能変更のPull Requestに分ける。
ローカル変数の名前の変更のような小さな整理は分けなくてよい。

Pull Requestを分割した場合は、次を書く。

- 分割した変更の一部であることを、Pull Requestの本文に書く。
- 残りの作業をIssueかPull Requestの本文に書き、レビュアーが作業漏れかどうかを判断できるようにする。

## 差分の大きさと改善

あるべき変更を先に決め、その後でレビューしやすいように差分を小さくできないかを検討する。
差分を小さくするために、可読性や保守性を上げるリファクタリングを見送らない。
差分が大きくなる場合は、Pull Requestの分割で対処する。

整形による空白だけの差分は、GitHubの差分表示のURLに`?w=1`を付けると除外できる。
空白だけの差分が多いPull Requestでは、本文に`?w=1`付きのURLを書く。

## ファイルの移動とリネーム

ファイルの移動やリネームと同時に、そのファイルの内容を大きく変更しない。
Gitが同一ファイルと判定できないと、削除と新規作成として扱われ、変更履歴を追えずレビューでも差分を確認できなくなる。

移動と編集を同じPull Requestに含める場合は、`git mv`で移動してcommitし、その後のcommitで内容を編集する。
squash mergeではPull Requestのcommitが一つにまとまるため、内容を大きく編集する場合は、移動だけのPull Requestを先にマージする。

## 出典

- フューチャー株式会社「コードレビューガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forCodeReview/code_review.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
