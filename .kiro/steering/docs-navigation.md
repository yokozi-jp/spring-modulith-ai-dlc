---
inclusion: always
name: docs-navigation
description: プロジェクトの規約、仕様、アーキテクチャ、手順を docs/ から探して読むためのナビゲーション。いつ docs を読むか、領域ごとの入口となる index.md、見つからないときの動き、docs や steering を更新するときに読む文書を示す。コードや設定、ドキュメントを変更するすべてのタスクで使用する。
---

# docs の読み方

規約、仕様、アーキテクチャ、Runbook の正文は `docs/` にある。
steering は入口の案内だけを持ち、規約の本文を持たない（[ADR-038](../../docs/adr/ADR-038-route-steering-to-docs-knowledge.md)）。

## いつ読むか

- コード、設定、ビルド、CI、ドキュメントを変更する前に、変更する領域の index.md を読む。
- 設計上の判断をする前に、`docs/adr/index.md` で既存の判断を確認する。
- 規約や既存の決定を推測で補わない。docs に書かれていることを確認してから書く。

## 領域ごとの入口

| 領域                                             | 入口                       |
| ------------------------------------------------ | -------------------------- |
| バックエンド（アーキテクチャ、テスト）           | `docs/backend/index.md`    |
| フロントエンド                                   | `docs/frontend/index.md`   |
| データベース（マイグレーション、jOOQ、接続）     | `docs/database/index.md`   |
| 日時とタイムゾーン                               | `docs/datetime/index.md`   |
| コンテナ（Dockerfile、Compose）                  | `docs/container/index.md`  |
| 開発ツール（Taskfile、フック、CI、Lint）         | `docs/tooling/index.md`    |
| 文章（日本語の技術文書）                         | `docs/writing/index.md`    |
| ナレッジ管理（docs と steering の役割分担）      | `docs/knowledge/index.md`  |
| エージェント設定                                 | `docs/agents/index.md`     |
| 設計判断（ADR）                                  | `docs/adr/index.md`        |
| 上記以外、どこか分からないとき                   | `docs/index.md`            |

領域ごとの steering（`backend.md` など）は、ケースごとに読む文書を示す。
index.md の各行には「いつ読むか」が書いてある。
該当する行の文書だけを読み、全文書を読み込まない。
文書のリンクをたどるときは iwe（`iwe_retrieve`、`iwe_find`）を使ってよい。

## 見つからないとき

1. index に該当する行がなければ、`iwe_find` の全文検索（`lexical`）で探す。
2. それでも見つからなければ、推測で進めず利用者に確認する。
3. 作業で判明した規約や知識は、docs への追加を利用者に提案する。

## docs や steering を更新するとき

- 知識をどの層へ置くか、依存方向や領域の入口を決める前に、`docs/knowledge/knowledge-architecture.md` を読む。
- docs を新規作成、編集、移動、分割する、または新しい領域を追加する前に、`docs/knowledge/documentation-authoring.md` を読む。
- steering を作成、編集する前に、`docs/knowledge/steering-authoring.md` を読む。
- 更新後は `task okf-check` を実行する。
