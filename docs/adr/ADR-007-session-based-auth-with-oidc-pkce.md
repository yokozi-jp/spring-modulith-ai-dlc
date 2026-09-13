# ADR-007: セッションベース認証と OIDC Authorization Code + PKCE

## Status

Accepted

## Date

2026-09-11

## Context

本 ADR は、既に実装済みの決定を遡って記録した（backfill）。

ブラウザ向けの Web アプリケーションで、認証を IdP（Keycloak）へ委譲しつつ、
XSS によるトークン窃取やトークン失効の難しさを避けたい。
SPA が保持する stateless な JWT は、失効・ローテーション・保管場所の問題を伴う。

## Decision

サーバサイド Web クライアントとして OIDC Authorization Code Flow + PKCE（S256）を採用し、
認証状態はサーバ側セッション（Spring Session + Redis）で保持する。

- 認可リクエストごとに PKCE の challenge（S256）を付与する。
- セッション ID は Cookie（`APP_SESSION`、`http-only` / `secure` / `SameSite=Lax`）でのみ送受信する。
- 更新系は CSRF トークン（Spring Security の SPA モード）で保護する。
- ログアウトはアプリケーションセッションを破棄し、IdP 対応時は SSO セッションも終了する。
- ヘルスチェック用エンドポイントだけを未認証で許可する。

## Consequences

### Positive

- アクセストークンをブラウザに保持せず、XSS によるトークン窃取面を減らせる。
- 失効・無効化がサーバ側セッションの破棄で完結する。
- 複数インスタンスでセッションを共有できる（Redis）。

### Negative

- セッションストア（Redis）への依存が増える。
- ステートレスではないため、セッション容量とストアの運用が要る。

### Neutral

- セッション Cookie の `secure` は環境で切り替える（本番 / STG は true、ローカルは false）。
- ALB 配下では `X-Forwarded-*` を解釈してスキーム・ホストを復元する。

## Alternatives Considered

### Alternative 1: stateless JWT を SPA が保持

- 説明：アクセストークンをブラウザで保持し API へ付与する。
- Pros：サーバがセッションを持たずスケールしやすい。
- Cons：XSS による窃取、失効・ローテーションの難しさ、保管場所の問題。

### Alternative 2: BFF でトークンを保持しつつ独自セッション

- 説明：BFF がトークンを保持し、ブラウザには独自セッションを渡す。
- Pros：本 ADR と方向性は近い。
- Cons：本プロジェクトは Spring Security の OAuth2 Client + Session で同等を得られ、
  追加コンポーネントを持たない構成を選好。

## References

- `backend/src/main/java/com/example/demo/SecurityConfig.java`
- `backend/src/main/resources/application.yaml`（`spring.security.oauth2.client`、`server.servlet.session`）
- `docker/keycloak/realm.json`
