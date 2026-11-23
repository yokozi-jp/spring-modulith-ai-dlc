# ログインの rate limit を、インスタンスをまたいで数える（#346）

調べた日：2026-11-23。こちらの版は Spring Boot 4.1.2、Spring Security 7.1.2、Spring Session 4.1.1、Keycloak 26.8.2。最新は Spring Security 7.2.0、Spring Boot 4.2.0（2026-11-19 公開）。

向こうは `yokozi-jp/agent-software-factory`（`29ba897`）。

## 問い

ログインの試行の上限を、API の複数のインスタンスをまたいで数えるべきか。

チケットは「こちら：Spring Security と Redis のセッションの構成」と書くが、ADR-111 で、セッションは PostgreSQL（`modulith.spring_session`）に保存している。Redis はバックエンドの依存にない。

## 結論の要約

見送り。アプリの中で数えるカウンタは足さない。理由は 3 つある。

1. こちらのアプリは資格情報を受け取らない。パスワードの総当たりが当たる場所は IdP（本番は Cognito、ローカルとテストは Keycloak）で、アプリの上限では守れない。
2. アプリが未認証で受ける経路（認可の開始とコールバック）には、IdP への問い合わせの増幅がなく（1 回の開始につき高々 1 回）、実際の影響は DB のセッションの行の増加である。これを数える仕組みは、数えること自体が追加の DB 書き込みになる。
3. `docs/web-api/edge-responsibilities.md` が、レート制限を入口（CDN、ALB、WAF）に置くと決めている。入口は全インスタンスの要求を 1 か所で数えるので、インスタンスをまたぐ問題がもともとない。

足すのは、未認証でセッションの行を作る経路を入口のレート制限の対象に決め、docs に書くこと。

## 向こうが何を決めたか

`apps/api/src/auth/auth.ts` は Better Auth の rate limit を有効にし、保存先を DB にする（`rateLimit: { enabled: true, storage: "database" }`）。理由は、API が複数のインスタンス（ECS のタスクと Lambda の実行環境）で動き、保存先がメモリだとインスタンスごとに別々に数え、インスタンスの数だけ上限を超えて通るため（#236）。窓と上限は Better Auth の既定（全体は 10 秒に 100 回、`/sign-in` で始まる経路は 10 秒に 3 回）。`src/auth/rate-limit.test.ts` は、2 つのプロセスに 4 回を交互に送り、`[200, 200, 200, 429]` になることを確かめる。

向こうが数える対象は、ブラウザが IdP へ移る前の `POST /api/auth/sign-in/social`（認可の開始）である。パスワードは向こうも受け取らない（メールとパスワードのログインは使わない）。つまり向こうの rate limit も、資格情報の総当たりではなく、未認証の経路の乱用を抑える。向こうは、ライブラリが rate limit と DB の保存先を持つので、数える仕組みを足す費用が小さい。

## こちらの実測

compose-test の backend（`spring-modulith-test-1`）に対して測った。

### アプリが未認証で受ける経路

| 要求                                                                 | 応答               | `spring_session` の行                                      |
| -------------------------------------------------------------------- | ------------------ | ---------------------------------------------------------- |
| `GET /oauth2/authorization/web`（Cookie なし）                       | 302 で IdP へ      | +1（`spring_session_attributes` に認可の要求 2262 バイト） |
| `GET /login/oauth2/code/web?code=x&state=y`（Cookie なし）           | 302 `/login?error` | +1                                                         |
| `GET /oauth2/authorization/nope`（登録のない id）                    | 500                | 0                                                          |
| `GET /api/memberships`、`/login`、`/error`、`/logout`（Cookie なし） | 401、200、500、200 | 0                                                          |

- 認可の開始は、`HttpSessionOAuth2AuthorizationRequestRepository` が認可の要求を保存するため、Cookie のない要求ごとに DB のセッションを 1 つ作る。
- 8 並列で 2000 回送ると 10.4 秒（約 190 回/秒）かかり、`spring_session` が 2006 行、`spring_session` と属性の表の合計が 0.14 MB から 4.65 MB に増えた（1 回あたり約 2.3 KB）。行は無操作の 30 分（`max_inactive_interval` 1800）残り、期限切れの削除は既定で毎分である。この値は、テストの DB（本番の大きさではない）で、同じ速度が 30 分続けば約 34 万行、約 0.8 GB になる計算である。
- IdP への問い合わせの増幅：有効な `state` を持つセッションで `code=bogus` のコールバックを送ると、Keycloak のトークン endpoint への要求が 1 回出て（`CODE_TO_TOKEN_ERROR` が 1 件）、同じセッションで再送すると IdP へは出ない（Keycloak のログの件数は 1 のまま）。認可の要求がコールバックで消えるためで、増幅は 1 回の開始につき高々 1 回である。

### 資格情報の総当たり

- アプリには資格情報を受ける endpoint がない（`SecurityConfig` の `oauth2Login` だけ）。
- 本番の Cognito：公式の docs（awsdocs/amazon-cognito-developer-guide の `amazon-cognito-user-pools-authentication-flow.md`）に、「5 回失敗すると 1 秒ロックし、以降は失敗のたびに倍にして最大約 15 分。ロック中の試行は期間に影響しない」とある。「この挙動は変わりうる」とも書かれている。ユーザープールに AWS WAF の web ACL を関連づけることもできる（`user-pool-waf.md`）。Fraud Control の account takeover prevention の rule group は関連づけられない。
- ローカルとテストの Keycloak：`docker/keycloak/realm.json` に `bruteForceProtected` がない（Keycloak の既定は無効）。ローカルの固定の利用者で E2E を何度も流すので、有効にするとロックが E2E を壊す。ローカルは対象外にする。

## フレームワークの仕組み

- Spring Security 7.2.0 の sources（`spring-security-web`、`spring-security-config`）に、rate limit の仕組みはない（`ratelimit` で探して 0 件）。7.2.0 のリリースノートにも関連の項目はない。
- Spring Cloud Gateway の `RequestRateLimiter` は Redis と Gateway の構成を前提にする。こちらは ADR-111 で Redis を外している。
- 自作かライブラリ（Bucket4j の JDBC 実装など）で数えると、未認証の要求ごとに DB の読み書きが増える。守りたいのが「未認証の要求による DB の書き込み」なので、守る仕組みが同じ DB に書く形は目的に合わない。入口は DB に触れずに断る。

## 判断

### 取り込まないもの

- アプリの中の rate limit（インスタンスごとのメモリのカウンタも、DB や Redis を使う分散のカウンタも）。上の 3 つの理由による。
- ローカルの Keycloak のブルートフォース検知（E2E のロック）。
- 認可の要求を DB のセッションに入れず、署名した Cookie に入れる自作（セキュリティの部品の自作になり、守る範囲が入口で足りる）。

### 取り込むもの

`docs/web-api/edge-responsibilities.md` に、入口のレート制限の対象として次を決める。

- 対象：`/oauth2/authorization/**` と `/login/oauth2/code/**`。未認証の要求 1 回が DB のセッションの行を 1 つ作る（約 2.3 KB を 30 分、実測）。
- 超過したら 429 と `Retry-After`（同じ docs の既存の決め）。
- 値は、本番の入口が決まるときに、ブラウザの送信量の判断と合わせて決める（#185 の入口の Issue が、この対象を受け入れ条件に持つ）。今は `infrastructure/` が空で、入口がない。
- ログインの総当たりは IdP が受け持つことを、同じ docs に書く。Cognito の挙動は変わりうるので、docs には docs への参照と確かめた日付を残す。

## 確かめられなかったこと

- 本番の入口（CloudFront、ALB、WAF）の構成と、rate-based rule の値。入口がまだない。
- Cognito のロックの挙動を実際に試していない。公式の docs の記述である。
- 認可の開始が秒間数百回を超えたときの、DB の書き込みと期限切れの削除の負荷。実測は 8 並列で約 190 回/秒、2000 回までである。
- `/oauth2/authorization/nope` が 500 になる点は、別の観察として残す（未認証で 500 と WARN を起こせる）。

## 参照

- 向こう：`apps/api/src/auth/auth.ts`、`apps/api/src/auth/rate-limit.test.ts`、`docs/adr/0022-better-auth-cookie-bff.md`。
- こちら：ADR-007、ADR-102、ADR-111、`docs/web-api/edge-responsibilities.md`、`docs/web-api/authentication-and-session.md`、`docker/keycloak/realm.json`。
- 外部：Amazon Cognito Developer Guide（`amazon-cognito-user-pools-authentication-flow.md`、`user-pool-waf.md`）、Spring Security 7.2.0 の sources。
