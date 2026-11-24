---
type: ADR
title: 'ADR-102: 本番の IdP に Amazon Cognito を使い、ローカルとテストでは Keycloak で代える'
description: 本番の IdP を Amazon Cognito のユーザープールにし、ローカルと CI では docker/keycloak/realm.json の Keycloak で代える決定。アプリは標準の OIDC の claim だけを読み、どちらの IdP の独自の機能にも依存しない。
tags: [adr, security, auth, oidc, idp]
---

# ADR-102: 本番の IdP に Amazon Cognito を使い、ローカルとテストでは Keycloak で代える

## Status

Proposed

## Date

2026-11-19

## Context

[ADR-007](ADR-007-session-based-auth-with-oidc-pkce.md) は、OIDC の Authorization Code Flow with PKCE でログインし、サーバー側のセッションで認証すると決めた。
ADR-007 は IdP を Keycloak と書いているが、本番の IdP をどれにするかは ADR に残していなかった。
`docker/compose.yml` の Keycloak の注記と [APIの認証、セッション、権限](../web-api/authentication-and-session.md)のログアウトの節は、本番を Cognito と想定して書かれている。

[issue #369](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369) は、テナントと役割をアプリの DB に持つと決めた（[ADR-101](ADR-101-keep-tenants-and-roles-in-app-database.md)）。
このとき、本番の IdP が Cognito であることを前提に、Keycloak の Organizations と Cognito のカスタム属性とグループを使わないと決めた（#363 の Q10）。
IdP の前提を ADR にしておかないと、ローカルの Keycloak だけにある機能に頼った変更を、レビューで止める根拠がない。

## Decision

本番の IdP に Amazon Cognito のユーザープールを使う。
ローカルと CI では、`docker/keycloak/realm.json` を読み込んだ Keycloak で代える。

アプリは、どちらの IdP でも、標準の OIDC の範囲だけを使う。

- ログインは ADR-007 の Authorization Code Flow with PKCE にし、IdP の discovery の文書から endpoint を読む。
- 利用者は ID トークンの `iss` と `sub` の組で識別する。IdP を移るときは、`tenancy` の ID 連携の表だけを書き換える（#374）。
- テナント、所属、役割を IdP に持たせない（ADR-101）。Cognito のカスタム属性とグループ、Keycloak の Organizations とレルムの役割を使わない。
- ローカルの realm には、ローカルの利用者と、アプリの client だけを置く。

## Consequences

### Positive

- 本番の IdP の運用（パスワードの管理、多要素認証、利用者の登録の画面）をマネージドサービスに任せられる。
- ローカルと CI は、外部のサービスなしで、本物の OIDC のログインを E2E と DAST で通せる。
- アプリが標準の claim だけを読むため、IdP を替えても、ID 連携の表の書き換えで済む。

### Negative

- ローカルと本番で IdP の実装が違う。Keycloak で動いた挙動が Cognito でも同じとは限らず、IdP に依存する挙動は Cognito でも確かめる必要がある。
- Cognito のログアウトが、Spring Security の `OidcClientInitiatedLogoutSuccessHandler` の前提（discovery の `end_session_endpoint`）に合うかは、まだ確かめていない（#316 が挙げた課題）。

### Neutral

- ID トークンの `email_verified` をアプリが本人の確認に使うときは、Keycloak と Cognito の両方で claim が入ることを、使い始める作業で確かめる。
- 本番のユーザープールの作り方と設定の値は、本番の構築の手順で決める。

## Alternatives Considered

### 選択肢1: 本番でも Keycloak を運用する

- **Description**：本番の環境に Keycloak を置き、ローカルと同じ realm を使う。
- **Pros**：ローカルと本番で IdP の挙動がそろう。
- **Cons**：IdP の可用性、更新、バックアップをアプリのチームが運用することになる。

### 選択肢2: ローカルとテストでも Cognito を使う

- **Description**：開発者と CI が、AWS のユーザープールに対してログインする。
- **Pros**：本番と同じ IdP で確かめられる。
- **Cons**：ローカルの開発と CI が AWS の資格情報とネットワークに依存し、E2E と DAST を隔離した環境で回せない。

## References

- [ADR-007: セッションベース認証と OIDC Authorization Code + PKCE](ADR-007-session-based-auth-with-oidc-pkce.md)
- [ADR-101: テナント、所属、役割をアプリの DB に持ち、テナントを選んだ時点で役割の権限を組み立てる](ADR-101-keep-tenants-and-roles-in-app-database.md)
- [issue #316](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/316)
- [issue #363](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/363)
- [issue #369](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369)
- [Amazon Cognito user pools](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools.html)
- `docker/keycloak/realm.json`
