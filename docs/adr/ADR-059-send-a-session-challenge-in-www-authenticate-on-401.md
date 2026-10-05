---
type: ADR
title: 'ADR-059: 401 の WWW-Authenticate に独自の Session challenge を返す'
description: Cookie セッションに合う登録済みの認証 scheme がないため、すべての 401 に WWW-Authenticate: Session realm="demo" を付ける決定。
tags: [adr, backend, api, security, auth]
---

# ADR-059: 401 の WWW-Authenticate に独自の Session challenge を返す

## Status

Accepted

## Date

2026-10-05

## Context

RFC 9110 §15.5.2 は、401 の応答に少なくとも一つの challenge を持つ `WWW-Authenticate` を付けることを MUST としている。
この API は同一オリジンの Cookie セッションで認証する（[ADR-007](ADR-007-session-based-auth-with-oidc-pkce.md)）。
未認証の `/api/**` は、ログイン画面への redirect ではなく 401 の Problem Details を返す（[ADR-013](ADR-013-standardize-http-api-contracts.md)）。
ログインは `/oauth2/authorization/web` から始まり、SPA はこの経路を知っている。

これまでの 401 は `WWW-Authenticate` を付けておらず、RFC 9110 に反していた。
IANA の HTTP Authentication Scheme Registry には、Cookie セッションを表す scheme がない（2026-10-05 に確認した。Basic、Bearer、Concealed、Digest、DPoP、GNAP、HOBA、Mutual、Negotiate、OAuth、PrivateToken、SCRAM-SHA-1、SCRAM-SHA-256、vapid）。

## Decision

すべての 401 に `WWW-Authenticate: Session realm="demo"` を付ける。

`Session` は登録されていない独自の scheme であり、RFC 9110 §11.1 の `auth-scheme`（token）の文法に合う。
`realm` は RFC 9110 §11.5 の `auth-param` で、quoted-string にする。
値の `demo` は `spring.application.name` と同じで、環境によって変えない。

値は `ApiProblemDetails` の一つの定数で持ち、応答ヘッダーを作る `responseHeaders` が 401 のときだけ付ける。
Security の entry point（`handlerExceptionResolver` 経由の `ApiExceptionHandler`）、MVC の例外、`/error` の 401 は、どれもこの処理を通る。
OpenAPI では共通の `UnauthorizedProblem` 応答にこのヘッダーを記述する。

## Consequences

### Positive

- 401 が RFC 9110 の MUST を満たす。
- 値を作る場所が一つで、401 の経路ごとに値がずれない。
- ブラウザは未知の scheme で認証の dialog を出さない。

### Negative

- 登録されていない scheme のため、汎用のクライアントは challenge に応答できない。
  この API のクライアントは同一オリジンの SPA だけであり、401 を受けたらログインへ遷移する。

### Neutral

- システム間連携の認証を追加するときは、その方式の scheme（例えば Bearer）を challenge に追加するかを、その ADR で決める。
- ヘッダーの追加は後方互換の変更であり、OpenAPI の `info.version` は MINOR の範囲で扱う。

## Alternatives Considered

### Basic を返す

- **Description**：`WWW-Authenticate: Basic realm="demo"` を返す。
- **Pros**：登録済みで、どのクライアントも解釈できる。
- **Cons**：ブラウザが資格情報の dialog を表示する。この API は Basic 認証を受け付けない。

### Bearer を返す

- **Description**：`WWW-Authenticate: Bearer realm="demo"` を返す。
- **Pros**：登録済みで、OAuth 2.0 と相性が良い。
- **Cons**：`Authorization` ヘッダーでトークンを送る方式を意味するが、この API はブラウザからのトークンを受け付けない（ADR-007）。

### ヘッダーを付けない

- **Description**：これまでどおり `WWW-Authenticate` を付けない。
- **Pros**：変更が要らない。
- **Cons**：RFC 9110 §15.5.2 の MUST に反する。

### ログイン URL を parameter で返す

- **Description**：`Session realm="demo", login="/oauth2/authorization/web"` のように、challenge にログインの経路を入れる。
- **Pros**：クライアントがログインの経路を知らなくてもよい。
- **Cons**：標準化された parameter ではない。SPA はすでに経路を知っており、使う側がない。

## References

- [RFC 9110 §11: HTTP Authentication](https://www.rfc-editor.org/rfc/rfc9110.html#section-11)
- [RFC 9110 §15.5.2: 401 Unauthorized](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.2)
- [IANA: HTTP Authentication Scheme Registry](https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml)
- [ADR-007](ADR-007-session-based-auth-with-oidc-pkce.md)
- [ADR-013](ADR-013-standardize-http-api-contracts.md)
- `backend/src/main/java/com/example/demo/error/presentation/web/ApiProblemDetails.java`
- `backend/src/main/java/com/example/demo/OpenApiConfig.java`
