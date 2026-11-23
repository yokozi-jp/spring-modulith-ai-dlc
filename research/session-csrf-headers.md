# セッションの期限、CSRF、セキュリティヘッダーの方式を比べる（#345）

調べた日：2026-11-23。こちらの版は Spring Boot 4.1.2、Spring Security 7.1.2、Spring Session 4.1.1。最新は Spring Boot 4.2.0、Spring Security 7.2.0、Spring Session 4.2.0（Maven Central の `maven-metadata.xml`、2026-11-19 公開）。

向こうは `yokozi-jp/agent-software-factory`（`29ba897`）。

## 問い

セッションの期限、CSRF の確かめ方、セキュリティヘッダー、ログアウトの後始末で、こちらに足すものはあるか。

## 結論の要約

| 項目                                      | 判断                                                                                                                                                        |
| ----------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| セッションの最長の期間（向こうは 8 時間） | 採用。こちらにはなく、実測で 9 時間前に作ったセッションが使えた                                                                                             |
| 無操作の期間（向こうは 30 分）            | 値は同じ。こちらは Spring Boot の既定で、どこにも書いていないので明示して試験で固定する                                                                     |
| `Origin` と `Sec-Fetch-Site` の検査       | 一部採用。CSRF の token は残し、その前に足す。Content-Type の検査は取り込まない                                                                             |
| セキュリティヘッダー                      | 一部採用。HSTS の `includeSubDomains` を ADR-014 に揃え、`Cross-Origin-Resource-Policy` と `Cross-Origin-Opener-Policy` を足す。API への CSP は取り込まない |
| `Clear-Site-Data`                         | 見送り。Chromium で戻る操作の後に保護された画面が出ず、`cache` に既知の不具合がある                                                                         |

## 1. セッションの期限

### 向こう

`apps/api/src/auth/auth.ts`：無操作で 30 分（`session.expiresIn`）、最長で 8 時間（`absoluteSessionSeconds`）。OWASP Session Management Cheat Sheet と ADR-0022 を根拠にしている。

### こちら

- 無操作の期間は、`application.yaml` に `server.servlet.session.timeout` がなく、Spring Boot の既定の 30 分になっている。`spring_session.max_inactive_interval` は 1800（実測）。`docs/web-api/authentication-and-session.md` は「合意したセッションの有効期間」と書くだけで、値はどこにもない。
- 最長の期間はない。実測：ログイン後のセッションの `creation_time` を 9 時間前に書き換えて `GET /api/memberships` を送ると 200 だった。最後のアクセスから 31 分前に書き換えると 401 になった。
- セッションの Cookie に `Max-Age` はない（`Set-Cookie: APP_SESSION=...; Path=/; HttpOnly; SameSite=Lax`）。同じ docs は「有効期間は `Expires` ではなく `Max-Age` で指定する」と書いており、実装と食い違う。

### フレームワークの仕組み

- Spring Session 4.2.0 と Spring Security 7.2.0 の sources に、最長の期間の仕組みはない（`getCreationTime` を期限の判定に使う箇所がない。Spring Session の期限は `maxInactiveInterval` だけ）。
- よって自作になる。代わる仕組みがないことが理由である。

### 自作の形が成り立つ根拠

- `creation_time` はセッションの ID を作り直しても変わらない。実測：テナントを選ぶ（`PUT /api/current-tenant`、セッション ID を作り直す）前後で `creation_time` が同じで、古い ID の行は消えた。
- ログインの前の認可の開始（`/oauth2/authorization/web`）でセッションが作られるので、最長の期間は認可の開始から数える。ログインに要する数秒から数分の差で、8 時間に対して無視できる。
- 時刻は `Clock` を受け取る（日時の規約）。

### Cookie の `Max-Age`

`Max-Age` を付けると Cookie が永続になり、ブラウザを閉じても残る。共有端末のリスクが増える。サーバー側で無操作と最長の期間を切るので、Cookie はブラウザのセッション Cookie のままにするほうがよい。docs の `Max-Age` の項を実装に合わせる。

### IdP との関係

アプリのセッションが切れて認可を始め直すと、IdP の SSO セッションが残っていれば、資格情報を入れずにログインが戻る。最長の期間は IdP の SSO セッションの上限（Keycloak と Cognito の設定）で上限が決まる。再認証を強制する（`prompt=login` や `max_age`）のは別の判断で、この調査の範囲に入れていない。

## 2. CSRF

### 向こう（`apps/api/src/http/middleware/csrf.ts`）

GET、HEAD、OPTIONS 以外で次を求める。`Origin` があれば公開のオリジンと同じ（403）。`Sec-Fetch-Site` があれば `same-origin`（403）。`Content-Type` は `application/json`（415）。token は使わない。ヘッダーがないときは通す。主な防御は Cookie の `SameSite=Strict` である。

### こちら

`csrf.spa()` と `CookieCsrfTokenRepository` で、`__Host-XSRF-TOKEN` の Cookie と `X-XSRF-TOKEN` ヘッダーを照合する（ADR-066）。ADR-066 は、この照合が naive double-submit cookie であると認め、「Fetch Metadata（`Sec-Fetch-Site`）と `Origin` の検査は、この ADR では決めない」と書いている。

実測（compose-test の backend、ログイン済みのセッション）：

| 要求                                                                                                                             | 結果                               |
| -------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| `PUT /api/current-tenant`、token なし                                                                                            | 403 `/problems/csrf-token-invalid` |
| 同じ要求に、Cookie と header で同じ値 `abc` を足し、`Origin: https://evil.localhost:21173`、`Sec-Fetch-Site: same-site` を付ける | 204（通った）                      |

2 行目は、同じ登録ドメインの別のホストが CSRF の Cookie を書けた場合を、サーバーの側だけで再現したものである。`__Host-` の名前はブラウザにこの書き込みを拒ませるので、プレフィックスを実装したブラウザでは起きない。残る危険は、プレフィックスを実装しないブラウザと、ADR-066 が前提とする「同じ登録ドメインに信頼できないホストを置かない」が破れたときである。

### フレームワークの仕組み

- Spring Security 7.2.0 に Fetch Metadata による CSRF の仕組みはない。要望は spring-projects/spring-security#18361（2025-12-25 起票、open、`status: waiting-for-triage`）にある。
- `Origin` が同一オリジンかの判定は、Spring Framework の `CorsUtils.isCorsRequest(HttpServletRequest)` が持つ。`Origin` とリクエストの scheme、host、port を比べ、`Origin: null` は別オリジンとして扱う。`forward-headers-strategy: framework` が X-Forwarded-* を反映するので、ALB の背後でも OIDC の `redirect_uri` と同じ前提で動く。Vite の proxy は `changeOrigin: false` で、ブラウザの Host をそのまま渡す。
- `Sec-Fetch-Site` の値の判定は 1 行である。
- よって自作は、これらを使う薄い filter になる。

### 判断

token は残し、その前に `Origin` と `Sec-Fetch-Site` の検査を足す（一部採用）。token は、ヘッダーのないクライアントでも効くので、向こうの「ヘッダーがなければ通す」より強い。足す検査は、ADR-066 が認めた naive double-submit の穴の 2 つ目の層になる。

- 取り込む：更新系（GET、HEAD、OPTIONS 以外）で、`Origin` があって別オリジンなら 403。`Sec-Fetch-Site` があって `same-origin` と `none` 以外なら 403。対象は `/api/**` と `/logout`。
- 取り込まない：`Content-Type` が `application/json` でなければ 415 にする検査。Spring MVC の `@RequestBody` の `consumes` が不正な型を 415 にし、token が form からの送信を止める。
- 取り込まない：Cookie の `SameSite=Strict`（向こうは RFC 10017 6.1.3.2 に従う）。こちらは認可の要求（`state` と PKCE の verifier）をセッションに保存し（`HttpSessionOAuth2AuthorizationRequestRepository`）、IdP から戻る cross-site のトップレベルの GET に `APP_SESSION` が付く必要がある。`Strict` だとログインが戻らない。向こうも `state` の Cookie だけを `Lax` にしている。

## 3. セキュリティヘッダー

### 実測（compose-test の backend、`X-Forwarded-Proto: https` を付けて HTTPS 相当にしたもの）

- 常に付く：`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: strict-origin-when-cross-origin`、`Permissions-Policy`、`Cache-Control: no-cache, no-store, max-age=0, must-revalidate`、`Pragma`、`Expires`、`X-XSS-Protection: 0`。
- HTTPS のときだけ：`Strict-Transport-Security: max-age=31536000 ; includeSubDomains`。
- 付かない：`Content-Security-Policy`、`Cross-Origin-Opener-Policy`、`Cross-Origin-Resource-Policy`。

### 向こうの API（`apps/api/src/app.ts`、Hono 4.13.17 の `secureHeaders`）

- 明示：`Content-Security-Policy: default-src 'none'`、`Strict-Transport-Security: max-age=63072000; includeSubDomains`、`nosniff`、`X-XSS-Protection` と `X-Frame-Options` は無効。
- 既定のまま：`Cross-Origin-Resource-Policy: same-origin`、`Cross-Origin-Opener-Policy: same-origin`、`Origin-Agent-Cluster: ?1`、`Referrer-Policy: no-referrer`、`X-DNS-Prefetch-Control: off`、`X-Download-Options: noopen`、`X-Permitted-Cross-Domain-Policies: none`。
- 向こうの SPA の配信には、CSP などのヘッダーの設定が見つからなかった（`apps/`、`packages/`、`infra/`、`docs/` で `Content-Security-Policy` を探した）。SPA のヘッダーは、こちらのほうが多い（`frontend/apps/web/vite.config.ts` と ADR-014、ADR-068）。

### 比べて分かったこと

1. **こちらの食い違い**：ADR-014 は「`includeSubDomains` は対象ドメインの全 subdomain が HTTPS 化されたことを確認してから有効にする」と決め、「`includeSubDomains` と `preload` を含む HSTS を初回リリースから返す」を退けた案に置く。しかし Spring Security の既定は `includeSubDomains` を付け、`ApiContractTest`（453 行目）がその値を固定している。ADR-014 と試験と実装のどれかを直す必要がある。
2. **`Cross-Origin-Resource-Policy: same-origin`**：Spring Security 7.2.0 は `HeadersConfigurer.crossOriginResourcePolicy` を持つ（`CrossOriginResourcePolicyHeaderWriter`）。API の応答を、別のオリジンの `<script>` や `<img>` に読ませない。同じサイトの別ホストからの no-cors の読み込みも断つ。1 行で足せる。
3. **`Cross-Origin-Opener-Policy: same-origin`**：SPA の HTML に付ける。配信点（`vite.config.ts` の `securityHeaders` と本番の CDN）の設定である。OIDC はリダイレクトの流れで、popup を使わない。実測：Playwright で HTML に COOP、`/api/**` に CORP を足して、ログイン、テナントの選択、トップの表示、ログアウト、`/logged-out` の表示まで通った。コンソールに COOP と CORP の警告も出なかった（Chromium 153、`http://localhost:21173`）。
4. **API への CSP（`default-src 'none'`）**：ADR-014 の「別の案 3」と同じで、取り込まない。JSON の応答は HTML として描画されず、`nosniff` と `X-Frame-Options: DENY` がある。Swagger UI も壊しうる。
5. **向こうが既定で付けるその他**：`X-DNS-Prefetch-Control`、`X-Download-Options`、`X-Permitted-Cross-Domain-Policies`、`Origin-Agent-Cluster` は取り込まない。古いブラウザや Flash などへの対策で、このアプリの脅威に当たらない。`Referrer-Policy` は、こちらの `strict-origin-when-cross-origin` のほうが OIDC のリダイレクトと両立する。
6. **HSTS の年数**：向こうは 2 年（63072000）。ADR-014 は 1 年（31536000）。hstspreload.org の preload の条件は 1 年以上で、preload は別判断なので、1 年のままにする。

## 4. ログアウトの後始末

### 向こう

`/api/auth/sign-out` の応答に `Clear-Site-Data: "cache", "cookies"` を付ける。戻る操作で保護された画面が見えないようにするためで、`sign-out.ts` に書かれている。

### こちら

`POST /logout` の実測（`X-Forwarded-Proto: https`）：302 で IdP の end_session へ（`id_token_hint` 付き）。`APP_SESSION` と `__Host-XSRF-TOKEN` を削除（`Expires=1970`、`Max-Age=0`）。古い `APP_SESSION` を送ると 401。`Clear-Site-Data` はない。API の応答は `no-store`。

### 戻る操作の実験

Playwright（Chromium 153）で、ログイン、テナントの選択、トップの表示、ログアウト、`/logged-out` に着いてから `goBack()` した。

| 条件                                                                    | 戻った先           |
| ----------------------------------------------------------------------- | ------------------ |
| `Clear-Site-Data` なし（bfcache を有効にした起動）                      | IdP のログイン画面 |
| `Clear-Site-Data: "cache", "cookies"` を `/logout` の応答に足す（同上） | IdP のログイン画面 |
| `Clear-Site-Data` なし（Playwright の既定。bfcache が無効）             | IdP のログイン画面 |

どの場合も `pageshow` の `persisted` は false で、保護された画面は出なかった。SPA が再読込され、認証がないので IdP へ移った。

### ブラウザの対応（mdn/browser-compat-data の `http/headers/Clear-Site-Data.json`）

- `cookies`：Chrome 61、Firefox 63、Safari 17。
- `cache`：Chrome は 127 以降も `partial_implementation`（タブの再読込まで cache から読まれる要求がある、`crbug.com/364634040`。数秒止まる、`crbug.com/40233601`）。Firefox は 138 以降（63 から 94 は削除）。Safari 17。

### 判断

見送り。サーバーはセッションと Cookie を消し、API の応答は `no-store` で、SPA は戻る操作で再読込されて認証を求める。`Clear-Site-Data` が足す利得を、実験では観測できなかった。`cache` には、利用者に数秒の停止を起こしうる既知の不具合がある。

### 確かめられなかったこと

- Safari と Firefox、実機。headless の Chromium が bfcache に入れる状態だったか（`persisted` が常に false だった理由）は確かめていない。bfcache に入れる状態で戻る操作が保護された画面を出すかは、未確認である。
- 本番の CDN と ALB の背後での `Origin` と `Host` の値。
- `CorsUtils` を使った filter の実装は書いていない。上の要求を実際に送って確かめたのは、現状の挙動である。

## 5. 追加の観察（この Issue の範囲外）

- `GET /oauth2/authorization/nope`（登録のない id）は 500 になる。認可の開始の filter が WARN を残して `sendError(500)` する。未認証の要求で 500 を起こせる。
- Spring Security 7.2.0 のリリースノート（GitHub の release）に、この調査の判断を変える項目はない。上げる必要はない。

## 参照

- 向こう：`apps/api/src/auth/auth.ts`、`apps/api/src/http/middleware/csrf.ts`、`apps/api/src/app.ts`、`apps/web/src/lib/sign-out.ts`、`docs/web-api/security.md`。
- こちら：`backend/src/main/java/com/example/demo/SecurityConfig.java`、ADR-007、ADR-014、ADR-066、ADR-111、`docs/web-api/authentication-and-session.md`、`frontend/apps/web/vite.config.ts`、`ApiContractTest`。
- 外部：spring-projects/spring-security#18361、Spring Security 7.2.0 と Spring Session 4.2.0 の sources、`org.springframework.web.cors.CorsUtils`（spring-web 7.0.10）、mdn/browser-compat-data。
- 実験の道具：`research/session-csrf-headers/`（ログインの script、戻る操作と COOP/CORP の Playwright の script。拡張子 `.txt` で保存している）。
