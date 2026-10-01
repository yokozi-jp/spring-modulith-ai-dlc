---
type: Convention
title: 連携の一覧と定義書
description: システムが持つ連携の一覧に書く項目と、連携ファイルごとの定義書に書く事項を定める規約。連携を追加または変更するとき、連携先とファイルの仕様を合意するときに読む。
tags: [convention, integration, interface, documentation, future-arch-guidelines]
---

# 連携の一覧と定義書

システムが持つすべての連携を I/F 一覧にまとめ、連携を変えるたびに更新する。
連携ファイルごとに定義書を作り、可能なら機微な情報を除いたサンプルファイルを連携先と共有する。

## I/F 一覧

- システムのすべての連携を I/F 一覧にまとめ、常に最新に保つ。
- 各連携に次の事項を書く。
  - 機能 ID と機能名
  - 連携先のシステム
  - 配信か集信か
  - 方式（オブジェクトストレージ、キューなど）
  - 頻度と時刻
  - 門限チェックの有無（集信の場合）
  - 冪等かどうか
  - 置き場所（バケットとキーの接頭辞）
  - 機密情報の有無
  - 説明

## ファイル定義書

- 連携ファイルごとに定義書を作る。
- 定義書には項目定義に加え、この領域の規約が連携仕様に書くと定めた事項を書く。
  - ソート条件と、集信側が並び順に依存してはならないこと（[連携ファイルのレイアウト](interface-file-layout.md)）
  - 取り消しを認めない期限（[連携ファイルのレイアウト](interface-file-layout.md)）
  - NULL の表し方と、変換できない文字の扱い（[連携ファイルの形式と CSV の書き方](interface-file-format.md)）
  - 機密情報を含むかどうか（[機密情報を含む連携ファイル](interface-file-security.md)）
- 可能なら、機微な情報を除いた実ファイルをサンプルとして連携先と共有する。
- スキーマは、JSON Schema や Data Contract Specification のような機械で読める形式で連携先へ渡してもよい。

## 出典

- フューチャー株式会社「I/F設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forIF/if_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
