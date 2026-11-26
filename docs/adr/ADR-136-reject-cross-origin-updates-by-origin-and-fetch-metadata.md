---
type: ADR
title: 'ADR-136: 更新系の要求の Origin と Sec-Fetch-Site を CSRF のトークンの前に確かめる'
description: /api/** と /logout の更新系の要求で、Origin か Sec-Fetch-Site が別のオリジンを示せば、CSRF のトークンを確かめる前に専用の type の 403 にし、403 のログに同じ type を付ける判断と、Content-Type の 415 と SameSite=Strict を取り込まない理由。
tags: [adr, security, csrf, backend]
---

# ADR-136: 更新系の要求の Origin と Sec-Fetch-Site を CSRF のトークンの前に確かめる

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

`/api/**` と `/logout` の更新系の要求で、`Origin` か `Sec-Fetch-Site` が別のオリジンを示せば、CSRF のトークンを確かめる前に 403 にする。
対象の要求と拒否する条件は、[別のオリジンからの更新の拒否](../web-api/authentication-and-session.md#別のオリジンからの更新の拒否)に書く。

CSRF のトークンの照合は残し、2 つの層にする。
ブラウザの外のクライアント（ZAP、Playwright の API の要求）はどちらの header も付けないので、どちらの header もない要求は通し、CSRF のトークンだけで守る。

検査は `SecurityConfig` が `CsrfFilter` の前に置く `CrossOriginRequestFilter` が行う。
拒否は `shared.security` の `CrossOriginRequestRejectedException` で表し、`/api/**` の `AccessDeniedHandler` に渡して、CSRF の失敗と同じ Problem Details の変換を通す。
type は `/problems/cross-origin-request-rejected` にし、`/problems/csrf-token-invalid` と区別する。
403 のログにも応答と同じ type を `problem.type` で付け、CSRF の失敗、別のオリジンからの更新の拒否、テナントを選ぶ前の拒否をログで見分けられるようにする。
属性は [ADR-127](ADR-127-derive-problem-types-from-failure-reasons.md) が 404、409、422 のログに付けるものと同じである。
SPA の `apiFetch` は `/problems/csrf-token-invalid` の 403 だけを再送するので、この 403 は再送しない。

Content-Type の検査（415）と `SameSite=Strict` は取り込まない（選択肢3、選択肢4）。

## Consequences

### Positive

- 同じ registrable domain の別の host が CSRF の Cookie を書けても、ブラウザから送る更新系の要求は `Origin` か `Sec-Fetch-Site` で止まる。
- 拒否の type が CSRF のトークンの失敗と分かれ、ログの `problem.type` も分かれるので、応答とログから原因を見分けられる。

### Negative

- 認証していない別のオリジンからの更新系の要求は、401 ではなく 403 になる。
- `Origin` と `Sec-Fetch-Site` を書き換えられるブラウザの外のクライアントには効かない。
  そのクライアントからは、CSRF のトークンの照合だけが守る。
- 配信に前提が増える。
  公開のホスト（`Host` か `X-Forwarded-Host`）、`X-Forwarded-Proto`、`X-Forwarded-Port` を、backend まで変えずに届ける必要がある。
  ALB は `X-Forwarded-Host` を付けないので、公開のホストは `Host` のまま届ける。
  CDN が `Host` を書き換える設定（CloudFront のオリジンリクエストポリシーの `AllViewerExceptHostHeader` など）では、正規の更新系の要求もすべて 403 になる。
  本番の配信の定義はまだリポジトリにないので、作るときにこの前提を満たす。

### Neutral

- Vite の proxy は `changeOrigin: false` で `Host` を保つので、開発環境、E2E、DAST の同じオリジンの要求は通る。
- Spring Security が Fetch Metadata の対策を持ったら、この filter をそれに置き換えるかを判断する。
- 別のオリジンのクライアントを許すときは、[ADR-014](ADR-014-use-same-origin-spa-security-boundary.md) とあわせてこの判断を改める。

## Alternatives Considered

### 選択肢1: CSRF のトークンの照合だけのままにする

- **Description**：ADR-066 のまま、`Origin` と `Sec-Fetch-Site` を確かめない。
- **Pros**：コードが増えない。
- **Cons**：prefix を実装しないブラウザと、信頼できない host を置かない前提が破れたときに止める層がない。
  調査の再現で 204 になった。

### 選択肢2: どちらの header もない更新系の要求も拒否する

- **Description**：`Origin` も `Sec-Fetch-Site` もない要求を 403 にする。
- **Pros**：header を落とす経路でも止まる。
- **Cons**：ZAP と Playwright の API の要求が止まり、古いブラウザと header を落とす proxy の正規の要求も止まる。
  CSRF のトークンの照合が残るので、得るものが小さい。

### 選択肢3: Content-Type が JSON でない更新系の要求を 415 にする

- **Description**：フォームで送れる `Content-Type` を拒否する。
- **Pros**：単純なフォームからの CSRF を、CSRF のトークンの前に止める。
- **Cons**：Spring MVC の `consumes` と CSRF のトークンが同じ役目を持つ。
  フォームの `Content-Type` で送れる要求は、CSRF のトークンがなければ照合で 403 になる。
  `/logout` はフォームの送信なので、対象から外す規則が要る。

### 選択肢4: Cookie を SameSite=Strict にする

- **Description**：`__Host-APP_SESSION` と `__Host-XSRF-TOKEN` を `Strict` にする。
- **Pros**：別のサイトからの要求に Cookie が付かない。
- **Cons**：同じサイトの別の host からの要求は止まらない。
  認可の要求をセッションに保存するので、IdP から戻る cross-site の callback に `__Host-APP_SESSION` が付かないと、保存した認可の要求を失う（[ADR-119](ADR-119-harden-session-cookie-with-host-prefix.md)）。

## References

- [ADR-066](ADR-066-harden-csrf-cookie-with-host-prefix.md)
- [ADR-119](ADR-119-harden-session-cookie-with-host-prefix.md)
- [ADR-127](ADR-127-derive-problem-types-from-failure-reasons.md)
- [ADR-014](ADR-014-use-same-origin-spa-security-boundary.md)
- [issue #682](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/682)
- [issue #345 の調査の記録](https://github.com/yokozi-jp/spring-modulith-ai-dlc/blob/research/session-csrf-headers/research/session-csrf-headers.md)
- [spring-projects/spring-security#18361](https://github.com/spring-projects/spring-security/issues/18361)
- [OWASP Cross-Site Request Forgery Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
- [W3C Fetch Metadata Request Headers](https://www.w3.org/TR/fetch-metadata/)
- [AWS: Using the managed origin request policies](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/using-managed-origin-request-policies.html)
- `backend/src/main/java/com/example/demo/CrossOriginRequestFilter.java`
