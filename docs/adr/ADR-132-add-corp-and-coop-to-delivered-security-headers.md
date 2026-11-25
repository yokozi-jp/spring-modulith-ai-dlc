---
type: ADR
title: 'ADR-132: 配信するセキュリティヘッダーに CORP と COOP を足し、HSTS を ADR-014 に揃える'
description: API の応答に Cross-Origin-Resource-Policy same-origin を、SPA の HTML に Cross-Origin-Opener-Policy same-origin を足し、HSTS から includeSubDomains を外して ADR-014 の決定どおりにする判断。
tags: [adr, security, spa, frontend, backend]
---

# ADR-132: 配信するセキュリティヘッダーに CORP と COOP を足し、HSTS を ADR-014 に揃える

## Status

Proposed

## Date

2026-11-25

## Context

[ADR-014](ADR-014-use-same-origin-spa-security-boundary.md)は、SPA の HTML 応答に付けるヘッダーの一覧と、API 応答の Spring Security の既定ヘッダーを決めた。
Issue #345 の調査で、この一覧と実装の間に 3 つの差が見つかった。

1. **HSTS が食い違っている。** ADR-014 は、`includeSubDomains` を対象ドメインの全 subdomain の HTTPS 化を確認してから有効にすると決め、`includeSubDomains` と `preload` を初回から返す案を退けた。Spring Security の既定は、HTTPS のとき `Strict-Transport-Security: max-age=31536000 ; includeSubDomains` を返す。`ApiContractTest` がその値を固定していた。
2. **API の応答に `Cross-Origin-Resource-Policy` が付かない。** 別のオリジンの `<script>` や `<img>` による読み込みと、同じサイトの別ホストからの no-cors の読み込みを、ブラウザが断てない。
3. **SPA の HTML に `Cross-Origin-Opener-Policy` が付かない。** 別のオリジンのウィンドウと browsing context group を共有する。

COOP が壊すのは、`window.opener` を通す popup 型のログインである。
このリポジトリの OIDC は Authorization Code Flow のリダイレクトで、popup を使わない。
Playwright（Chromium 153）で、HTML に COOP `same-origin`、`/api/**` に CORP `same-origin` を足し、ログイン、テナントの選択、トップの表示、ログアウト、`/logged-out` まで通り、コンソールに警告が出ないことを確かめた。

## Decision

HSTS は ADR-014 の決定に従い、`includeSubDomains` を付けない。
`SecurityConfig` に `max-age=31536000` と `includeSubDomains(false)` を明示し、Spring Security の既定へ戻らないようにする。
max-age は 1 年のままにする。

バックエンドは、API の応答に `Cross-Origin-Resource-Policy: same-origin` を付ける。
`SecurityConfig` の `HeadersConfigurer.crossOriginResourcePolicy` で設定し、`ApiContractTest` で固定する。

SPA の HTML 応答には、配信点で `Cross-Origin-Opener-Policy: same-origin` を付ける。
ADR-014 が定めた、配信点が正本で同じヘッダーを複数層から重複して付けないという扱いに従う。
Vite の開発サーバーと preview は `securityHeaders` で付ける。

ADR-014 の「最終的な SPA の HTML 応答に付けるヘッダー」の一覧に、`Cross-Origin-Opener-Policy: same-origin` を足す。
配信点が満たす条件は[ビルドと配信](../frontend/build-and-delivery.md)の「配信点の条件」に書く。

次は取り込まない。

- API への CSP（ADR-014 の「Alternative 3」）。
- `X-DNS-Prefetch-Control` などの古いヘッダー。
- HSTS の 2 年への延長。

## Consequences

### Positive

- 別のオリジンのページが、API の応答を `<script>` や `<img>` で読み込めなくなる。
- SPA のウィンドウが、別のオリジンのウィンドウと browsing context group を共有しなくなる。
- HSTS が ADR-014 と一致し、試験が固定する。`includeSubDomains` が戻れば `ApiContractTest` が落ちる。

### Negative

- popup でログインする IdP を使うときは、COOP を `same-origin-allow-popups` へ緩める判断が要る。
- API の応答を別のオリジンの no-cors で読み込む利用ができなくなる。ADR-014 が別オリジンのクライアントを許可しないので、新しい制約にはならない。

### Neutral

- COOP は配信点が正本なので、CDN やリバースプロキシへ移すときは、その層で同じ値を付ける。
- `includeSubDomains` を付ける条件の正本は ADR-014 である。条件を満たして付けるときは、この ADR の HSTS の記述を改める。

## Alternatives Considered

### Alternative 1: HSTS に includeSubDomains を付けたままにし、ADR-014 を改める

- **Description**：Spring Security の既定を受け入れ、ADR-014 の `includeSubDomains` の扱いを変える。
- **Pros**：コードの変更が要らない。
- **Cons**：全 subdomain の HTTPS 化を確認できていない。誤りをブラウザ側から短期間で取り消せない。

### Alternative 2: CORP に same-site を使う

- **Description**：同じサイトの別ホストからの読み込みを許す。
- **Pros**：サイト内の別ホストが API の応答を読み込める。
- **Cons**：SPA と API は同一オリジンで、別ホストから API を読む要件がない。許可を広げる理由がない。

### Alternative 3: COOP を same-origin-allow-popups にする

- **Description**：自分が開いた popup との関係だけを残す。
- **Pros**：popup 型のログインへ後から対応しやすい。
- **Cons**：OIDC はリダイレクトで popup を使わない。使わない機能のために分離を弱めない。

## References

- [ADR-014](ADR-014-use-same-origin-spa-security-boundary.md)
- [Spring Security 7.2: Security HTTP Response Headers](https://docs.spring.io/spring-security/reference/7.2/servlet/exploits/headers.html)
- [MDN: Cross-Origin-Resource-Policy](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cross-Origin-Resource-Policy)
- [MDN: Cross-Origin-Opener-Policy](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cross-Origin-Opener-Policy)
- `backend/src/main/java/com/example/demo/SecurityConfig.java`
- `frontend/apps/web/vite.config.ts`
