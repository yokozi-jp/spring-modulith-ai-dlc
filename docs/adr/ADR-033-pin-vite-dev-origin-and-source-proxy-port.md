# ADR-033: Vite 開発オリジンを固定し proxy ポートを単一ソース化する

## Status

Proposed

## Date

2026-09-30

## Context

ADR-014 は、SPA、API、OIDC の開始 URL と callback、logout を単一オリジンで公開し、ローカル開発でもブラウザは Vite の一つのオリジンだけへ接続する方針を定めた。
ローカルの Keycloak realm は、redirect URI、web origin、baseUrl、logout 後 URI をすべて `http://localhost:5173` に固定している。
バックエンドの OAuth2 Client は redirect URI を `{baseUrl}/login/oauth2/code/{registrationId}` として、ブラウザから見えるオリジンから組み立てる。

しかし、Vite 開発サーバーはポートを明示しない場合、5173 が使用中だと次の空きポートへ自動的に移動する。
移動すると、ブラウザのオリジンが Keycloak の登録値と食い違い、認証フローが redirect URI 不一致で失敗する。

proxy の転送先ポートも、`vite.config.ts` に `18080` を直書きしていた。
一方、開発バックエンドの待受ポートはリポジトリルートの `.env` の `SERVER_PORT` が正本であり、両者は独立に定義されて追従しなかった。
このルート `.env` は OIDC クライアントシークレットや DB パスワードなどの秘密情報も含む。

## Decision

Vite 開発サーバーのポートを `5173` に固定し、`server.strictPort: true` を設定する。
5173 が使用中のときは別ポートへ移らず起動を失敗させ、Keycloak の redirect URI と同じオリジンを保証する。

proxy の転送先ポートは、`loadEnv(mode, "..", "SERVER_PORT")` でルート `.env` の `SERVER_PORT` だけを読み込んで組み立てる。
プレフィックスを `SERVER_PORT` に限定し、同じファイルの秘密情報をクライアントバンドルへ露出させない。
値は 10 進整数かつ 1 から 65535 の範囲を設定読込時に検証し、範囲外や非数値は起動を失敗させる。
`.env` が無い CI などでは、現行の標準値 `18080` へフォールバックする。

proxy の `changeOrigin` は `false` を維持し、フロントエンド側の Host をバックエンドへ渡して `{baseUrl}` を開発オリジンに揃える。

開発オリジン、proxy への `SERVER_PORT` 反映、不正値の拒否、開発と本番の CSP 差分は、`vite.config.ts` の設定テストで固定する。

## Consequences

### Positive

- 開発サーバーのオリジンが常に `5173` になり、Keycloak の redirect URI 不一致による認証失敗を防げる。
- proxy 転送先が `SERVER_PORT` を正本にし、ポート定義の二重管理をなくせる。
- 不正な `SERVER_PORT` を起動時に検出でき、誤ったポートへ黙って転送しない。
- ルート `.env` の秘密情報を読み込まずに、proxy に必要なポートだけを参照できる。

### Negative

- 5173 が他プロセスに使われていると開発サーバーが起動しない。使用中プロセスの停止か、意図的な設定変更が必要になる。
- 開発者ごとに待受ポートを変える運用では、各自の `.env` に `SERVER_PORT` を設定する必要がある。

### Neutral

- 本番配信では CloudFront などがパスで振り分けるため、この proxy は効かない。設定は開発専用である。
- 設定テストは Vite+ の `createServer` と `resolveConfig` を使い、実際の解決結果を検証する。

## Alternatives Considered

### Alternative 1: ポートを明示せず Vite の自動移動に任せる

- Description：`server.port` と `strictPort` を設定しない。
- Pros：設定が短い。
- Cons：5173 が使用中だと別ポートで起動し、Keycloak の登録オリジンと食い違って認証が失敗する。

### Alternative 2: proxy ポートを引き続き直書きする

- Description：`vite.config.ts` に転送先ポートをハードコードする。
- Pros：`loadEnv` を使わず単純。
- Cons：`SERVER_PORT` と乖離し、開発者ごとにポートを変えると追従しない。

### Alternative 3: `loadEnv` でプレフィックスを絞らずルート `.env` を読む

- Description：プレフィックスを空にしてルート `.env` 全体を読み込む。
- Pros：追加のキー指定が不要。
- Cons：OIDC クライアントシークレットや DB パスワードなどの秘密情報を Vite の環境変数として読み込み、クライアントへ露出する恐れがある。

## References

- [ADR-014: SPA とバックエンドを同一オリジンで公開する](./ADR-014-use-same-origin-spa-security-boundary.md)
- [Vite: Server Options](https://vite.dev/config/server-options)
- [Vite: Shared Options（`loadEnv`）](https://vite.dev/config/shared-options)
- `frontend/vite.config.ts`
- `frontend/vite-config.test.ts`
- `docker/keycloak/realm.json`
