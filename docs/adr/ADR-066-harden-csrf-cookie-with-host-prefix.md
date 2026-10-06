---
type: ADR
title: 'ADR-066: CSRF の Cookie を __Host- の名前にし、SameSite=Lax を付ける'
description: Spring Security の csrf.spa() が発行する CSRF の Cookie を __Host-XSRF-TOKEN（Secure、Path=/、Domain なし、HttpOnly なし、SameSite=Lax）にし、SPA は GET、HEAD、OPTIONS 以外の要求にだけ X-XSRF-TOKEN を付ける決定と、ローカルの HTTP の Safari を対象外にする扱い。
tags: [adr, security, csrf, cookie, frontend, backend]
---

# ADR-066: CSRF の Cookie を __Host- の名前にし、SameSite=Lax を付ける

## Status

Accepted

## Date

2026-10-06

## Context

[ADR-007](ADR-007-session-based-auth-with-oidc-pkce.md) は、API の認証をセッションの Cookie にし、CSRF の対策を Spring Security に任せると決めた。
[ADR-014](ADR-014-use-same-origin-spa-security-boundary.md) は、SPA と backend を同一 origin で公開し、SPA が `X-XSRF-TOKEN` header を送ることを許した。
backend は `csrf.spa()` を使い、`CookieCsrfTokenRepository` の既定の `XSRF-TOKEN` Cookie を発行していた。

この形には 2 つの問題があった。

- `csrf.spa()` の照合は、OWASP の分類でいう naive double-submit cookie である。header の値と Cookie の値が一致すれば通るので、同じ registrable domain の別の host や平文の HTTP の応答から Cookie を書き込まれると、照合を通られうる（[issue #105](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/105)）。
- 既定の Cookie は `SameSite` を持たず、ZAP の passive scan が 10054（Cookie without SameSite Attribute）を出していた（[issue #125](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/125)）。

また、SPA の API client（`apiFetch`）は `X-XSRF-TOKEN` header を付けておらず、更新系の API を SPA から呼べなかった。

issue #105 は、方式の前提を ADR-014 に書くとしていた。
ADR-014 の決定は同一 origin で公開することであり、CSRF の Cookie の名前と属性はその内側の別の判断なので、ADR-014 を Superseded にせず、前提をこの ADR に置く。

### 環境ごとの確認の結果

`__Host-` の Cookie は `Secure` を要求し、RFC 6265bis は `Secure` の Cookie を安全な URL の応答からだけ受け付けるとする。
`http://localhost` を安全とみなすかは user agent に委ねられている。
そこで、backend と同じ `Set-Cookie`（`__Host-XSRF-TOKEN=<uuid>; Path=/; Secure; SameSite=Lax`）を返す代わりの backend を Vite の proxy の後ろに置き、各ブラウザで次の 4 点を確かめた。

1. fetch の応答の Cookie を受け付け、`document.cookie` で読める。
2. 同一 origin の fetch の POST に Cookie が付き、読んだ値を header で送れる。
3. 別 site の IdP から redirect されたトップレベルの callback の Cookie を受け付ける。
4. 同一 origin のトップレベルの form の POST（ログアウト）に Cookie が付く。

| 環境         | 起動の形                                              | Chromium     | Firefox      | WebKit（参考値） |
| ------------ | ----------------------------------------------------- | ------------ | ------------ | ---------------- |
| ローカル開発 | `vp dev`、`http://localhost:5173`                     | 4 点とも成立 | 4 点とも成立 | 4 点とも不成立   |
| E2E          | `vp preview --mode test`、`http://localhost:5173`     | 4 点とも成立 | 4 点とも成立 | 4 点とも不成立   |
| DAST         | `vp preview --mode test --host 127.0.0.1 --port 4173` | 4 点とも成立 | 4 点とも成立 | 4 点とも不成立   |

WebKit が拒むのは prefix ではなく `Secure` である。
同じ応答で属性の違う Cookie を返した対照では、次の結果になった。

| Set-Cookie                                          | WebKit | Chromium | Firefox |
| --------------------------------------------------- | ------ | -------- | ------- |
| `XSRF-TOKEN=v; Path=/`                              | 保存   | 保存     | 保存    |
| `LAX-TOKEN=v; Path=/; SameSite=Lax`                 | 保存   | 保存     | 保存    |
| `SECURE-TOKEN=v; Path=/; Secure; SameSite=Lax`      | 拒否   | 保存     | 保存    |
| `__Secure-TOKEN=v; Path=/; Secure; SameSite=Lax`    | 拒否   | 保存     | 保存    |
| `__Host-XSRF-TOKEN=v; Path=/; Secure; SameSite=Lax` | 拒否   | 保存     | 保存    |

版は Chromium 151 と 153、Firefox 155、WebKit 26.6（Playwright 1.63 の Linux の build）である。
WebKit の Cookie の保存は Linux では libsoup が受け持ち、macOS の Safari とは実装が違うので、この結果は参考値である。
Safari の確定には macOS の検証機が要り、まだ確かめていない。
`localhost` でない HTTP の origin では、Chromium も Cookie を保存しなかった（受け入れが prefix の未実装によるものでないことの対照）。

ZAP 2.17.0 は、HTTP の要求に `Secure` の Cookie を自分では付けなかった。
DAST の httpsender script は、要求に Cookie がなければ最後に受け取った `Set-Cookie` の値を Cookie と header の両方に付けるので、ZAP の state に依存せずに動く。

## Decision

- backend は CSRF の Cookie を `__Host-XSRF-TOKEN` にし、`Secure`（環境によらない）、`Path=/`、`SameSite=Lax` を付け、`Domain` と `HttpOnly` を付けない。`csrf.spa()` の後で `csrfTokenRepository(...)` を呼んで差し替える。
- header の名前（`X-XSRF-TOKEN`）と form の項目名（`_csrf`）は変えない。
- `SameSite` は `APP_SESSION` と同じ `Lax` にする。
- SPA は要求のたびに Cookie を読み、同じ origin への GET、HEAD、OPTIONS 以外の要求にだけ、マスクしない値を `X-XSRF-TOKEN` header で送る。Cookie がなければ header を付けずに送り、backend の 403 に任せる。
- ローカルの HTTP の開発環境は Chromium と Firefox で確かめ、Safari は HTTPS の環境で確かめる。

### 方式の前提

- 照合は naive double-submit cookie であり、署名した token（signed double-submit cookie）ではない。
- `__Host-` が Cookie の書き込みを塞ぐのは、ブラウザが cookie prefix を実装していることが前提である。
- 同じ registrable domain に信頼できない host を置かない。`APP_SESSION` は prefix を持たないため、その host からの書き込みは塞げない。
- Cookie の名前と header の名前は、frontend（`src/lib/csrf.ts`）、backend（`SecurityConfig`）、ZAP の httpsender script の 3 者の契約である。言語が違うので 1 か所にまとめられず、契約テスト、frontend の単体テスト、E2E、DAST の preflight がそれぞれのずれを検出する。
- Fetch Metadata（`Sec-Fetch-Site`）と `Origin` の検査は、この ADR では決めない。

## Consequences

### Positive

- 同じ registrable domain の別の host や平文の HTTP の応答から、CSRF の Cookie を書き込めなくなる（cookie prefix を実装したブラウザに限る）。
- ZAP の 10054 が CSRF の Cookie から消える。
- SPA から更新系の API を呼べるようになる。

### Negative

- ローカルの HTTP の Safari では CSRF の Cookie が保存されず、更新系の要求とログアウトが 403 になる。`XSRF-TOKEN`（`Secure` なし）の時は動いていたので、この環境は後退する。
- `localhost` 以外の HTTP の origin（LAN の IP で Vite に接続するなど）では、どのブラウザでも更新系の要求が 403 になる。[ADR-033](ADR-033-pin-vite-dev-origin-and-source-proxy-port.md) が origin を `localhost:5173` に固定しているので、もともと対象外である。
- DAST は、ZAP の httpsender script が Cookie と header を補うことに依存する。

### Neutral

- ローカルの Safari が必要になったら、ローカルの Vite を HTTPS にする（証明書の生成と信頼、ADR-033 の origin と Keycloak の redirect URI の変更を伴う）。
- macOS の Safari が `http://localhost` の `Secure` の Cookie を受け付けると確かめられたら、この ADR の表と `docs/frontend/browser-support.md` の 1 文を消す。

## Alternatives Considered

### 選択肢1: XSRF-TOKEN のまま SameSite だけ付ける

- **Description**：Cookie の名前を変えず、`SameSite=Lax` だけを足す。
- **Pros**：ローカルの HTTP の Safari でも動き、変更が backend の 1 か所で済む。
- **Cons**：#125 の 10054 は消えるが、#105 の書き込みの問題が残る。

### 選択肢2: 環境ごとに Cookie の名前を変える

- **Description**：ローカルだけ `Secure` なしの `XSRF-TOKEN` を使う。
- **Pros**：ローカルの HTTP の Safari でも動く。
- **Cons**：frontend、backend、ZAP の契約が環境で分かれ、E2E と DAST が本番の名前を通らなくなる。

### 選択肢3: ローカルの Vite を HTTPS にする

- **Description**：開発環境も HTTPS にして、どのブラウザでも `Secure` の Cookie を受け付けさせる。
- **Pros**：Cookie の名前と属性を保ったまま、ローカルの Safari でも動く。
- **Cons**：証明書の生成と信頼の設定、TLS の plugin の依存、ADR-033 の origin と Keycloak の redirect URI の変更が要り、CSRF の Cookie の判断の範囲を超える。

### 選択肢4: SameSite=Strict

- **Description**：`Lax` の代わりに `Strict` を付ける。
- **Pros**：クロスサイトから来たトップレベルの GET にも Cookie が載らない。
- **Cons**：GET は CSRF の検査の対象外なので守れるものが増えず、`APP_SESSION` と値がずれる。

### 選択肢5: session 方式（HttpSessionCsrfTokenRepository）

- **Description**：token をセッションに保存し、SPA には別の経路で渡す。
- **Pros**：Cookie の書き込みで照合を通られる問題が起きない。
- **Cons**：SPA が token を読む経路（応答の header や専用の endpoint）が要り、`csrf.spa()` の既定の形から外れる。#105 で比べ、Cookie の補強を選んだ。

## References

- [issue #105](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/105)
- [issue #125](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/125)
- [RFC 6265bis: Cookies: HTTP State Management Mechanism](https://datatracker.ietf.org/doc/draft-ietf-httpbis-rfc6265bis/)（cookie prefix と `Secure` の受け入れ）
- [W3C Secure Contexts](https://www.w3.org/TR/secure-contexts/)
- [OWASP Cross-Site Request Forgery Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
- [ADR-007](ADR-007-session-based-auth-with-oidc-pkce.md)
- [ADR-014](ADR-014-use-same-origin-spa-security-boundary.md)
- [ADR-056](ADR-056-run-authenticated-dast-with-zap-in-ci.md)
