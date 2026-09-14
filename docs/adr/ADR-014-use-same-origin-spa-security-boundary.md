# ADR-014: SPA とバックエンドを同一オリジンで公開する

## Status

Accepted

## Date

2026-09-14

## Context

認証は OIDC Authorization Code Flow とサーバ側セッションを使い、ブラウザは `APP_SESSION` Cookie と CSRF token をバックエンドへ送る。
この構成はアクセストークンを SPA に渡さない一方、SPA と API を別オリジンにすると credentialed CORS、Cookie 属性、preflight、OIDC redirect URI の設定が増える。

現在の Vite 開発サーバーには proxy がなく、本番の SPA 配信先とリバースプロキシも決まっていない。
CORS は未設定なので、Vite とバックエンドを別ポートのままブラウザから直接接続する構成は動作しない。

Spring Security は HSTS、`X-Content-Type-Options`、`X-Frame-Options` などを既定で付けるが、CSP、Referrer-Policy、Permissions-Policy はアプリケーション固有の値を決められないため既定では付けない。
CSP は HTML 文書が読み込めるスクリプトなどを制約するものであり、JSON API 応答へ一律に付けても、別の配信元から提供する SPA は保護できない。
セキュリティヘッダは最終的にブラウザへ HTML を返す配信点で保証する必要がある。

## Decision

本番では、SPA、API、OIDC の開始 URL と callback、logout を一つの HTTPS origin で公開する。
入口のリバースプロキシまたは CDN はパスで SPA と Spring Boot へ振り分ける。
API は `/api/**`、認証関連は `/oauth2/**`、`/login/**`、`/logout`、エラーは `/error` とする。

ローカル開発でも、ブラウザは Vite の一つの origin だけへ接続し、Vite proxy が API と認証関連パスをバックエンドへ転送する。
OIDC の redirect URI と post logout URI は、ブラウザから見えるこの origin に揃える。

同一オリジンを維持する間は CORS を有効にしない。
「未設定だから動いている」のではなく、cross-origin browser client を許可しないことを契約テストで確認する。

将来、別オリジンのブラウザクライアントが必要になった場合は本 ADR を再評価する。
その場合は Spring Security より前に CORS を処理し、次を満たす `CorsConfigurationSource` を単一箇所に定義する。

- 設定から読み込んだ完全一致の HTTPS origin だけを許可する。
- credential を許可するときに `*` origin を使わない。
- `/api/**` だけを対象にし、必要な method と request header だけを許可する。
- `APP_SESSION` Cookie を使うため `allowCredentials` を明示する。
- CSRF 保護を無効にせず、`X-XSRF-TOKEN` を許可する。
- preflight、許可 origin、不許可 origin、credential 付き要求を契約テストで固定する。

最終的な SPA の HTML 応答には、配信点で次のヘッダを付ける。
Spring Boot が SPA も配信する場合は Spring Security が配信点となり、CDN またはリバースプロキシが配信する場合はその層が正本となる。
同じヘッダを複数層から重複して付けない。

```text
Content-Security-Policy: default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
```

CSP ではインライン script と `unsafe-eval` を許可しない。
外部接続、Web Worker、画像 CDN などが実際に必要になった場合だけ、該当 directive へ個別に追加する。
Swagger UI は業務 SPA ではなく、既定の CSP と両立しない可能性があるため、本番では無効のまま維持し、開発環境でも SPA の CSP を緩める理由にしない。

HSTS は HTTPS の最終応答を返す配信点で `max-age=31536000` を設定する。
`includeSubDomains` は対象ドメインの全 subdomain が HTTPS 化されたことを確認してから有効にし、`preload` は運用上の取り消しコストを評価した別判断とする。

Spring Security の既定ヘッダは無効化しない。
バックエンドが直接返す API 応答では、少なくとも `nosniff`、`X-Frame-Options: DENY`、Referrer-Policy、Permissions-Policy と、HTTPS 時の HSTS を MockMvc で検証する。
CSP は SPA の HTML 応答で検証し、JSON API に存在することは要求しない。
本番では入口を経由した smoke test でも最終ヘッダを検証する。

## Consequences

### Positive

- credentialed CORS と cross-site Cookie の設定を持たずに、セッションと CSRF token を送受信できる。
- OIDC callback、API、SPA の origin が一致し、redirect URI と Cookie の不整合を減らせる。
- CSP を実際に script を実行する HTML 応答へ適用できる。
- cross-origin browser client は明示的な設計変更なしに API を利用できない。

### Negative

- 本番とローカルの両方で、パスを振り分ける proxy が必要になる。
- SPA と API を独立した origin で配信する構成へ変える場合は、CORS、Cookie、CSRF、OIDC 設定を再設計する必要がある。
- 厳格な CSP に対応していない UI ライブラリを採用すると、nonce または配信方法の見直しが必要になる。

### Neutral

- API と SPA は同じ origin でも、別プロセスまたは別サービスとして配備できる。
- `X-Frame-Options: DENY` と CSP の `frame-ancestors 'none'` は同じ制約を重ねるが、旧ブラウザとの互換性のため両方を維持する。
- HSTS の `includeSubDomains` と `preload` は、アプリケーションコードだけでは安全性を判断できない。

## Alternatives Considered

### Alternative 1: SPA と API を別オリジンで公開する

- Description：SPA から credentialed CORS で API を呼ぶ。
- Pros：静的配信と API を独立したドメインで運用できる。
- Cons：許可 origin、preflight、Cookie、CSRF、OIDC redirect URI の組合せが増え、現在の要件では利益がない。

### Alternative 2: CORS ですべての origin を許可する

- Description：開発と将来のクライアントのため、広い CORS 設定を先に入れる。
- Pros：異なる origin から接続しやすい。
- Cons：セッション Cookie を使う API で許可範囲を広げ、不要な攻撃面と設定事故を生む。

### Alternative 3: CSP を Spring Security から全応答へ付ける

- Description：API、Swagger UI、SPA の区別なく同じ CSP を返す。
- Pros：設定箇所を一つにできる。
- Cons：JSON API への CSP は別配信の SPA を保護せず、Swagger UI など無関係な文書を壊す可能性がある。

### Alternative 4: HSTS preload を直ちに有効にする

- Description：`includeSubDomains` と `preload` を含む HSTS を初回リリースから返す。
- Pros：HTTP への downgrade をドメイン全体で強く防げる。
- Cons：全 subdomain の HTTPS 保証と長期運用が未確認であり、誤設定をブラウザ側から短期間で取り消せない。

## References

- [Spring Security 7.1: Security HTTP Response Headers](https://docs.spring.io/spring-security/reference/7.1/servlet/exploits/headers.html)
- [Spring Security 7.1: CORS](https://docs.spring.io/spring-security/reference/7.1/servlet/integrations/cors.html)
- [OWASP HTTP Headers Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/HTTP_Headers_Cheat_Sheet.html)
- [`ADR-007`](./ADR-007-session-based-auth-with-oidc-pkce.md)
- `backend/src/main/java/com/example/demo/SecurityConfig.java`
- `frontend/vite.config.ts`
- `docker/keycloak/realm.json`
