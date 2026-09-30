---
type: Convention
title: steering、docs、iwe の役割分担
description: プロジェクトの知識を steering、docs、iwe の3層に分ける規約。各層の役割、依存方向、docs を読む流れ、領域単位の入口構造を定める。知識をどの層へ置くか、層間の依存と入口を設計するときに読む。
tags: [convention, documentation, steering, okf, knowledge-management]
---

# steering、docs、iwe の役割分担

知識は3層に分け、依存は steering から docs への一方向にする。
規約の正文は docs に置き、steering は「いつ、どの docs を読むか」だけを持つ。
ただし、エージェント設定ではルートの `AGENTS.md` が steering を兼ねる。
docs は領域ごとのフォルダに分け、各フォルダの index.md を入口にする。
この分担は [ADR-038](../adr/ADR-038-route-steering-to-docs-knowledge.md) で決定している。

## 3つの層

- **steering**（`.kiro/steering/`）：Kiro のナビゲーション。いつ docs を読むか、どの領域ならどの index.md を読むか、行動原則、全タスクで守る短いルール、Kiro 固有の使い方を書く。
- **docs**（`docs/`）：プロジェクトの知識。仕様、アーキテクチャ、コーディング規約の詳細、ADR、ドメイン知識、Runbook、API と DB のルール、日時や認証などの方針の正文を書く。
- **iwe**：docs を検索し、リンクを追跡するエンジン。グラフは `docs/` の中で閉じる。

steering に書いた知識は iwe の検索とリンク検査の対象にならない。
そのため、短いルールを steering に置く場合も、正文は docs に置いて steering からパスを示す。
エージェントが繰り返す手順は docs の Runbook を正文とし、skill は Runbook を参照する。

## 依存方向

- docs から `.kiro/` 配下へリンクしない。
- steering から docs を指すときは、パスを文字列（`docs/backend/index.md` など）で書く。
- steering で `#[[file:docs/...]]` を使わない。展開した docs は、その steering が読み込まれるたびに全文がコンテキストに入る。
- steering からソースコードや設定ファイルを `#[[file:...]]` で参照するのはよい。

## 読む流れ

エージェントは次の順で docs を読む。

1. steering（エージェント設定ではルートの `AGENTS.md`）で、作業する領域と読むべき index.md を決める。
2. 領域の index.md で、「いつ読むか」が作業に該当する行の文書を選ぶ。
3. 必要なら iwe（`iwe_retrieve`、`iwe_find`）でリンクをたどり、関連する文書だけを読む。

index.md に該当する行がなければ、`iwe_find` の全文検索で探す。
それでも見つからなければ、推測で進めず利用者に確認する。
作業で判明した知識は、docs への追加を提案する。

## 領域と入口

docs は領域ごとのフォルダ（`docs/backend/`、`docs/database/` など）に分ける。
各領域の index.md を、その領域にある文書の入口にする。
`docs/index.md` は、各領域の index.md と領域に属さない単独文書への入口になる。

通常の領域では、`.kiro/steering/<領域>.md` が対象ファイルと領域の index.md を対応づける。
`docs/knowledge/` では常時読み込みの steering `docs-navigation` が領域の steering を兼ねる。
`docs/agents/` ではルートの `AGENTS.md` が領域の steering を兼ねる。

## 関連する規約

docs の作成、編集、移動、index.md の形式、新しい領域の追加は [docs 文書の作成と変更](documentation-authoring.md) に従う。
steering の作成、編集、行数、inclusion モードは [steering の書き方](steering-authoring.md) に従う。
ADR の運用は [ADR の運用ルール](../adr/conventions.md) に従う。
検査コマンドと検査範囲は [Lint・テストのリファレンス](../lint-and-test.md) を参照する。
