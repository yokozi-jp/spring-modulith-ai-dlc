---
type: Reference
title: レビュー観点と参照する規約
description: 変更の内容をレビューするときの観点と、観点ごとに照らす正文の規約文書を示す一覧。人またはエージェントが差分のセキュリティ、アーキテクチャ、規約違反、仕様との不一致を確認するときに読む。
tags: [reference, code-review]
---

# レビュー観点と参照する規約

ツールで検出できる問題はレビューコメントにせず、[レビューで人が確認する範囲](review-scope.md)に従ってCIの検査へ寄せる。
この一覧は観点ごとに正文の文書を示すだけであり、規約の本文は各文書を読む。
正文がない観点は「未整備」と示す。

## 仕様の充足

- [Issue tracker](../agents/issue-tracker.md)

## アーキテクチャ

- [バックエンドアーキテクチャ](../backend/architecture.md)
- [バックエンドの層の責務](../backend/layers.md)
- [フロントエンドアーキテクチャ](../frontend/architecture.md)
- [Architecture Decision Records](../adr/index.md)

## セキュリティ

- [APIの認証、セッション、権限](../web-api/authentication-and-session.md)
- [APIの入力検証の配置](../web-api/validation.md)
- [画面の認可制御](../frontend/authorization-ui.md)
- [ブラウザに保持するデータとキャッシュ](../frontend/browser-storage-and-cache.md)
- [非同期メッセージのセキュリティ](../integration/async-message-security.md)
- [機密情報を含む連携ファイル](../integration/interface-file-security.md)
- [DB接続情報とロール分離](../database/connections.md)
- [PostgreSQLのロールと監査](../database/postgresql-roles-and-audit.md)
- [可観測性データの規約](../observability/conventions.md)
- [ADR-007: セッションベース認証と OIDC Authorization Code + PKCE](../adr/ADR-007-session-based-auth-with-oidc-pkce.md)
- [ADR-014: SPA とバックエンドを同一オリジンで公開する](../adr/ADR-014-use-same-origin-spa-security-boundary.md)
- [ADR-015: 可観測性データを構造化し保護する](../adr/ADR-015-structure-and-protect-observability-data.md)
- バックエンドの認可（エンドポイントやメソッド単位の権限判定）：未整備

## APIの契約と互換性

- [Web API](../web-api/index.md)
- [APIの互換性と廃止](../web-api/versioning.md)

## DBの変更

- [DBマイグレーション規約](../database/migrations.md)
- [PostgreSQLのロックを抑えるスキーマ変更](../database/postgresql-online-schema-change.md)
- [DBのデプロイと切り戻し](../database/runbook-deploy-and-rollback.md)

## 日時

- [日時](../datetime/index.md)

## ログと可観測性

- [可観測性データの規約](../observability/conventions.md)

## 非同期処理と連携

- [システム連携と非同期処理](../integration/index.md)

## テスト

- [バックエンドのテスト種別](../backend/testing-strategy.md)
- [テスト観点の割り当て](../frontend/test-strategy.md)
