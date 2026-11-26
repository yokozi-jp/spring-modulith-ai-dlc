---
type: ADR
title: 'ADR-134: 更新系の要求の Origin と Sec-Fetch-Site を CSRF の token の前に確かめる'
description: /api/** と /logout の更新系の要求で、Origin が別のオリジンか、Sec-Fetch-Site が same-origin と none 以外なら、CSRF の token を確かめる前に専用の type の 403 にする判断と、Content-Type の 415 と SameSite=Strict を取り込まない理由。
tags: [adr, security, csrf, backend]
---

# ADR-134: 更新系の要求の Origin と Sec-Fetch-Site を CSRF の token の前に確かめる

## Status

Proposed

## Date

2026-11-26

## Context

[ADR-066](ADR-066-harden-csrf-cookie-with-host-prefix.md) は、`csrf.spa()` の照合が naive double-submit cookie であると認め、「Fetch Metadata（`Sec-Fetch-Site`）と `Origin` の検査は、この ADR では決めない」とした。
照合は、Cookie の値と `X-XSRF-TOKEN` header の値が一致すれば通る。
`__Host-` の名前が Cookie の書き込みを塞ぐのは、ブラウザが cookie prefix を実装し、同じ registrable domain に信頼できない host を置かないときに限る。

Issue #345 の調査で、compose-test の backend にログインした状態で、`PUT /api/current-tenant` に Cookie と header で同じ値 `abc`、`Origin: https://evil.localhost:21173`、`Sec-Fetch-Site: same-site` を付けると 204 になった。
同じ registrable domain の別の host が CSRF の Cookie を書けた場合の再現である。
prefix を実装しないブラウザと、信頼できない host を置かない前提が破れたときに、照合のほかに止める層がない。

Spring Security 7.2.0 には Fetch Metadata による CSRF の対策がない（[spring-projects/spring-security#18361](https://github.com/spring-projects/spring-security/issues/18361)、open）。
`Origin` が同じオリジンかの判定は、Spring Framework の `CorsUtils.isCorsRequest(HttpServletRequest)` が持つ。
この判定は `Origin: null` を別のオリジンとみなす。
`server.request.header.forwarded.strategy: framework` の `ForwardedHeaderFilter` が Spring Security の filter chain より先に動くので、判定は X-Forwarded-* が示す公開の scheme、host、port と比べる。

## Decision

`/api/**` と `/logout` への GET、HEAD、OPTIONS 以外の要求で、次のどちらかに当てはまれば、CSRF の token を確かめる前に 403 にする。

- `Origin` があり、`CorsUtils.isCorsRequest` が true になる。解釈できない `Origin` も、同じオリジンだと示せないので拒否する。
- `Sec-Fetch-Site` があり、値が `same-origin` と `none` のどちらでもない。

どちらの header もない要求は通し、CSRF の token の照合に任せる。
token の照合は残し、2 つの層にする。
ブラウザの外のクライアント（ZAP、Playwright の API の要求）はどちらの header も付けないので、token だけで守る。

検査は `SecurityConfig` が `CsrfFilter` の前に置く `CrossOriginRequestFilter` が行う。
拒否は `shared.security` の `CrossOriginRequestRejectedException` で表し、`SecurityConfig.resolveOrSendError` で CSRF の失敗と同じ Problem Details の変換へ渡す。
type は `/problems/cross-origin-request-rejected` にし、`/problems/csrf-token-invalid` と区別する。
SPA の `apiFetch` は `/problems/csrf-token-invalid` の 403 だけを再送するので、この 403 は再送しない。

次は取り込まない。

- **Content-Type の検査（415）**：Spring MVC の `consumes` と token が同じ役目を持つ。フォームの `Content-Type` で送れる要求は、token がなければ CSRF の照合で 403 になる。
- **`SameSite=Strict`**：認可の要求をセッションに保存するので、IdP から戻る cross-site の GET に `__Host-APP_SESSION` が付く必要がある（[ADR-119](ADR-119-harden-session-cookie-with-host-prefix.md)）。

## Consequences

### Positive

- 同じ registrable domain の別の host が CSRF の Cookie を書けても、ブラウザから送る更新系の要求は `Origin` か `Sec-Fetch-Site` で止まる。
- 拒否の type が token の失敗と分かれるので、ログと応答から原因を見分けられる。

### Negative

- 認証していない別のオリジンからの更新系の要求は、401 ではなく 403 になる。
- `Origin` と `Sec-Fetch-Site` を書き換えられるブラウザの外のクライアントには効かない。token の照合だけが守る。
- 公開のオリジンと backend が受け取る Host が食い違う配信（X-Forwarded-* を付けない proxy など）では、正規の更新系の要求も 403 になる。

### Neutral

- Spring Security が Fetch Metadata の対策を持ったら、この filter をそれに置き換えるかを判断する。
- 別のオリジンのクライアントを許すときは、[ADR-014](ADR-014-use-same-origin-spa-security-boundary.md) とあわせてこの判断を改める。

## Alternatives Considered

### 選択肢1: token の照合だけのままにする

- **Description**：ADR-066 のまま、`Origin` と `Sec-Fetch-Site` を確かめない。
- **Pros**：コードが増えない。
- **Cons**：prefix を実装しないブラウザと、信頼できない host を置かない前提が破れたときに止める層がない。調査の再現で 204 になった。

### 選択肢2: どちらの header もない更新系の要求も拒否する

- **Description**：`Origin` も `Sec-Fetch-Site` もない要求を 403 にする。
- **Pros**：header を落とす経路でも止まる。
- **Cons**：ZAP と Playwright の API の要求が止まり、古いブラウザと header を落とす proxy の正規の要求も止まる。token が残るので、得るものが小さい。

### 選択肢3: Content-Type が JSON でない更新系の要求を 415 にする

- **Description**：フォームで送れる `Content-Type` を拒否する。
- **Pros**：単純なフォームからの CSRF を、token の前に止める。
- **Cons**：Spring MVC の `consumes` と token が同じ役目を持つ。`/logout` はフォームの送信なので、対象から外す規則が要る。

### 選択肢4: Cookie を SameSite=Strict にする

- **Description**：`__Host-APP_SESSION` と `__Host-XSRF-TOKEN` を `Strict` にする。
- **Pros**：別のサイトからの要求に Cookie が付かない。
- **Cons**：同じサイトの別の host からの要求は止まらない。IdP から戻る callback でセッションに保存した認可の要求を失う（ADR-119）。

## References

- [ADR-066](ADR-066-harden-csrf-cookie-with-host-prefix.md)
- [ADR-119](ADR-119-harden-session-cookie-with-host-prefix.md)
- [ADR-014](ADR-014-use-same-origin-spa-security-boundary.md)
- [issue #682](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/682)
- [issue #345 の調査の記録](https://github.com/yokozi-jp/spring-modulith-ai-dlc/blob/research/session-csrf-headers/research/session-csrf-headers.md)
- [spring-projects/spring-security#18361](https://github.com/spring-projects/spring-security/issues/18361)
- [OWASP Cross-Site Request Forgery Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
- [W3C Fetch Metadata Request Headers](https://www.w3.org/TR/fetch-metadata/)
- `backend/src/main/java/com/example/demo/CrossOriginRequestFilter.java`
