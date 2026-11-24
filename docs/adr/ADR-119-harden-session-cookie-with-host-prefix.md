---
type: ADR
title: 'ADR-119: セッションの Cookie を __Host-APP_SESSION にし、SameSite=Lax を保つ'
description: セッションの Cookie の名前を APP_SESSION から __Host-APP_SESSION（Secure、Path=/、Domain なし、HttpOnly、SameSite=Lax）に変え、SameSite=Strict にしない理由と、SESSION_COOKIE_SECURE を廃止する扱い。
tags: [adr, security, session, cookie, backend]
---

# ADR-119: セッションの Cookie を __Host-APP_SESSION にし、SameSite=Lax を保つ

## Status

Proposed

## Date

2026-11-24

## Context

[ADR-066](ADR-066-harden-csrf-cookie-with-host-prefix.md) は CSRF の Cookie を `__Host-XSRF-TOKEN` にし、同じ registrable domain の別の host から書き込めないようにした。
セッションの Cookie の `APP_SESSION`（[ADR-007](ADR-007-session-based-auth-with-oidc-pkce.md)）は prefix を持たないため、同じ書き込みを塞げず、session fixation の経路になりうる（[issue #159](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/159)）。

`__Host-` は `Secure`、`Path=/`、`Domain` なしを要求する。
セッションの `Secure` は環境変数 `SESSION_COOKIE_SECURE` で切り替えていた（ローカルは `false`）。
ADR-066 は、`http://localhost` の `Secure` の Cookie を Chromium と Firefox が保存することを確かめ、Safari を対象外にした。
ローカルの開発と E2E のセッションの Cookie も、この確認の範囲で動く。

DAST（[ADR-056](ADR-056-run-authenticated-dast-with-zap-in-ci.md)）は、この確認の範囲に入らない。
ZAP 2.17.0 の Firefox は `localhost` の通信も ZAP の proxy に通し（`network.proxy.allow_hijacking_localhost`）、そのとき `http://localhost` を安全とみなさず、`Secure` の Cookie を捨てる。
ZAP 自身も、HTTP の要求に `Secure` の Cookie を付けない（ADR-066）。
同じ image と plan の設定で、`Secure` の有無だけを変えた代わりの app に対して確かめた結果は次のとおり。

| 構成                                                                           | ブラウザのログイン | ZAP の requestor の GET と POST |
| ------------------------------------------------------------------------------ | ------------------ | ------------------------------- |
| `Secure` なし、`sessionManagement: autodetect`                                 | 成立               | 404（認証済み）                 |
| `Secure` あり、`autodetect`                                                    | 不成立             | 401                             |
| `Secure` あり、Firefox の pref、`autodetect`                                   | 成立               | 401                             |
| `Secure` あり、Firefox の pref、`sessionManagement: headers`                   | 成立               | 401                             |
| `Secure` あり、Firefox の pref、`sessionManagement: script`、httpsender script | 成立               | 404                             |

Firefox の pref は `network.proxy.testing_localhost_is_secure_when_hijacked` である。

## Decision

- セッションの Cookie の名前を `__Host-APP_SESSION` にする。`Secure`（環境によらない）、`Path=/`、`HttpOnly`、`SameSite=Lax` を付け、`Domain` を付けない。
- `SESSION_COOKIE_SECURE` を廃止し、`server.servlet.session.cookie.secure` を `true` に固定する。
- `SameSite` は `Lax` のまま `Strict` にしない。
- ADR-007 の Cookie の名前と属性（`APP_SESSION`、`secure`）を、この ADR が改める。
- ログアウトでは `deleteCookies` を使わず、無効にしたセッションの Cookie を Spring Session に消させる。`deleteCookies` は `Secure` を `request.isSecure()` で決めるので、HTTP では `__Host-` の削除の条件を満たさない。
- DAST は、ZAP の Firefox に `network.proxy.testing_localhost_is_secure_when_hijacked` を設定し、`sessionManagement` を script にする。httpsender script（`docker/zap/scripts/httpsender/carry-host-cookies.js`）が最後に受け取ったセッションの Cookie を保存し、session management script（`docker/zap/scripts/session/app-session.js`）が ZAP の要求に付ける。

### SameSite=Strict にしない理由

認可コードの callback は、IdP から来るトップレベルの GET である。
`Strict` の Cookie は、別 site から始まるトップレベルの遷移に付かない（RFC 6265bis）。
Spring Security は既定で、認可リクエスト（`state` と PKCE の `code_verifier`）をセッションに保存し、callback で取り出す。
IdP が別 site なら、callback にセッションの Cookie が付かず、認可リクエストが見つからずにログインが失敗する。

ローカルと E2E の Keycloak は `localhost` と `127.0.0.1` で、アプリと同じ site になりうるため、この失敗は E2E で見えない。
本番の IdP（Cognito、[ADR-102](ADR-102-use-amazon-cognito-as-production-idp.md)）は別 site なので、本番でだけ起きる。
この判断は仕様と Spring Security の既定の動作からの推論であり、別 site の IdP で実測していない。

`Lax` でも、更新系の要求（POST など）には Cookie が付かないので、CSRF の防御に足りる。
`Strict` で得るのは、別 site から来た GET に Cookie が付かないことだけで、ADR-066 の選択肢 4 と同じく守れるものが増えない。

## Consequences

### Positive

- 同じ registrable domain の別の host や平文の HTTP の応答から、セッションの Cookie を書き込めなくなり、session fixation の経路が閉じる（cookie prefix を実装したブラウザに限る）。
- `Secure` の有無が環境で分かれなくなり、環境変数が 1 つ減る。

### Negative

- ローカルの HTTP の Safari ではセッションの Cookie が保存されず、ログインできない。ADR-066 の CSRF の Cookie と同じ制約である（[issue #158](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/158)）。
- デプロイの時点で、`APP_SESSION` を持つ利用者のセッションは使えなくなり、再ログインが要る。
- 外部の検査や手順書が `APP_SESSION` を名前で指していれば、直しが要る。
- DAST は、ZAP の Firefox の pref と 2 つの script に依存する。pref の名前は Firefox の試験用で、Firefox や ZAP の版で変わりうる。

### Neutral

- `Strict` にするには、認可リクエストを Cookie 以外に保存する別の仕組みが要る。必要になったら別の ADR で決める。

## Alternatives Considered

### 選択肢1: APP_SESSION のまま、ADR-066 の前提に残す

- **Description**：名前を変えず、同じ registrable domain に信頼できない host を置かない前提を保つ。
- **Pros**：変更がない。
- **Cons**：session fixation の経路が残る。

### 選択肢2: SameSite=Strict

- **Description**：`Lax` の代わりに `Strict` を付ける。
- **Pros**：別 site から来た GET にも Cookie が載らない。
- **Cons**：別 site の IdP からの callback でセッションを失い、ログインが失敗する。守れるものが増えない。

### 選択肢3: ローカルだけ prefix なしにする

- **Description**：`Secure` と同じく、環境変数で名前を切り替える。
- **Pros**：ローカルの Safari でも動く。
- **Cons**：frontend、E2E、ZAP の契約が環境で分かれ、本番の名前を E2E と DAST が通らなくなる（ADR-066 の選択肢 2 と同じ）。

## References

- [issue #159](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/159)
- [issue #158](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/158)
- [RFC 6265bis: Cookies: HTTP State Management Mechanism](https://datatracker.ietf.org/doc/draft-ietf-httpbis-rfc6265bis/)（cookie prefix と SameSite）
- [ADR-007](ADR-007-session-based-auth-with-oidc-pkce.md)
- [ADR-056](ADR-056-run-authenticated-dast-with-zap-in-ci.md)
- [ADR-066](ADR-066-harden-csrf-cookie-with-host-prefix.md)
- [ADR-102](ADR-102-use-amazon-cognito-as-production-idp.md)
