# 認証まわりの画面とエラーの画面を比べる（#336 の調査）

この文書は #336 の調査の記録で、merge しない。
結論は #336 のコメントと同じで、作業は #643、#644、#645、#646 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `3ba2640`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（`origin/main`、commit `ee8bf60`）である。

## 結論

一部採用とする。

向こうの `apps/web` は、ログインの失敗、セッション切れ、ログアウト直後、403、404 の画面と文言を、向こうの Issue #397 と #399 の決定として実装している（向こうの PR #541、#564）。
こちらは、ログアウト直後の画面（`/logged-out`）と、存在しないものと見えないものを同じ 404 にする扱いが、すでに同じ決定になっている。
次の 4 つは足りないので取り込む。

- **ログインの失敗**（#643）：失敗すると、バックエンドが生成した英語の既定のログイン画面（`/login?error`）に着く。
  SPA の画面に替える。
- **ログインの後の戻り先**（#644）：ログインを求められた画面ではなく、常に `/` に戻る。
  戻り先を保持して戻す。
- **操作の途中のセッション切れ**（#645）：理由を伝えずにすぐ IdP へ移す。
  成功の後に 401 を受けたときだけ、理由と「操作は完了していない」ことを伝える画面を挟む。
- **権限不足の 403**（#646）：管理者の画面だけが専用の 403 の画面を持ち、ほかの 403 は「再試行」つきの一般のエラーになる。
  再試行を出さない共通の 403 の画面にする。

次は取り込まない。

- SPA の `/login` の画面と、`GET /api/me` による route の guard。
- `type` による 401 の `unauthenticated` と `session-expired` の区別。
- ログイン失敗の識別子（`account_unavailable` など）と、その識別子の URL への載せ方。
- 未ログインの 404 の「ログインへ」と、メニューの中の 404。
- 戻り先を調べる向こうの文字列の検査。
  同じ性質を、URL API で解決して origin を比べる形で確かめる。

404 の扱いと、ログアウト直後の画面は、すでに同じ決定なので変えない。

## 比べたもの

### 向こう

- `apps/web/src/routes/`：`login.tsx`、`$.tsx`、`_authenticated.tsx`、`_authenticated/_accounting.tsx`、`__root.tsx`
- `apps/web/src/lib/`：`login-message.ts`、`login-redirect.ts`、`api-error.ts`、`forbidden.ts`、`session.ts`、`sign-out.ts`
- `apps/web/src/components/`：`login-screen.tsx`、`error-screens.tsx`、`route-error.tsx`
- `apps/web/src/router.ts`、`apps/web/src/main.tsx`
- `packages/i18n/src/locales/ja/translation.json`、`packages/i18n/src/infrastructure/problem.ts`
- `apps/api/src/http/middleware/session.ts`、`apps/api/src/http/middleware/csrf.ts`、`apps/api/src/http/route/me.ts`
- `docs/web-frontend/auth.md`、`docs/web-frontend/authorization.md`、`docs/adr/0022-better-auth-cookie-bff.md`
- 向こうの Issue #397（画面の一覧と遷移）、#399（失敗と 403 と 404 の文言）、#406（ログインと guard）、#415（文言の実装）、向こうの PR #541、#564

`docs/web-frontend/auth.md` は、フューチャーの Web フロントエンド設計ガイドラインの認証方式 4 つの比較で、画面と文言の規約を持たない（`auth.md:14-16`、`:41-42`）。
`docs/web-frontend/authorization.md` の認可の目的は UX の向上であり、安全性はバックエンドの認可にある（`authorization.md:14`、`:22`）。

### こちら

- `frontend/apps/web/src/routes/`：`logged-out.tsx`、`_authenticated/route.tsx`、`_authenticated/_tenant/route.tsx`、`_authenticated/_tenant/members.tsx`、`_authenticated/_tenant/orders/$orderId/index.tsx`
- `frontend/apps/web/src/components/route-error.tsx`、`route-not-found.tsx`、`frontend/apps/web/src/router-defaults.ts`、`frontend/apps/web/src/api/query-client.ts`
- `frontend/packages/api/src/api-fetch.ts`
- `frontend/apps/web/src/features/tenancy/`
- `backend/src/main/java/com/example/demo/SecurityConfig.java`、`tenancy/presentation/web/TenantSelectionSuccessHandler.java`、`SignInOidcUserService.java`
- `docs/frontend/`（`routing-and-state.md`、`api-client-orval.md`、`url-design.md`、`authorization-ui.md`、`browser-storage-and-cache.md`、`i18n.md`）、`docs/web-api/authentication-and-session.md`、`status-codes.md`
- ADR-007、ADR-014、ADR-016、ADR-059、ADR-062

## 構造の違い

向こうは、SPA が `/login` の画面を持つ。
ログインは `authClient.signIn.social({ provider, callbackURL })` の呼び出しで始める（`main.tsx:33-36`）。
未ログインの利用者は、すべて `/login` の 1 ボタンの画面を経て IdP へ移る。

こちらは、ログインの開始が GET `/oauth2/authorization/web` で、`/login/**` は [ADR-014](../docs/adr/ADR-014-use-same-origin-spa-security-boundary.md) でバックエンドへ振り分ける予約パスである（`docs/frontend/url-design.md:33-35`、`frontend/apps/web/vite.config.ts:161`）。
SPA は 401 を受けると、画面を挟まずに IdP へ全画面で遷移する（`api-fetch.ts:133-145`）。
そのため、向こうの `/login?redirect=…&reason=…&error=…` の 1 つの画面が持つ 3 つの役割（戻り先を運ぶ、理由を伝える、失敗を伝える）を、こちらでは別々に足す必要がある。

## サーバーの応答と画面の対応

こちらのサーバーの応答は、`SecurityConfig` の filter chain を MockMvc で通して確かめた（「確かめたこと」）。

| サーバーの応答                                                          | こちらの今の画面                                                                                                                                                                       | 向こう                                                                                                                                     | 取り込んだ後                                                                                             |
| ----------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------- |
| 401、`about:blank`、`WWW-Authenticate: Session realm="demo"`（ADR-059） | `redirectToLoginOnUnauthorized` が `/oauth2/authorization/web` へ全画面で遷移する。30 秒以内の 2 回目は遷移せず `RouteError`（`api-fetch.ts:133-145`、`routing-and-state.md:103-104`） | `unauthenticated` と `session-expired` の 2 つの type。どちらも `/login` へ移す（API の `middleware/session.ts:40-65`、`router.ts:16-23`） | 最初の 401 は今のまま IdP へ。成功の後の 401 は `/session-expired`（#645）。どちらも戻り先を持つ（#644） |
| 403、`/problems/tenant-not-selected`                                    | `/tenants` へ案内する（`_tenant/route.tsx:28-39`、`route-error.tsx:14-25`）                                                                                                            | なし                                                                                                                                       | 変えない                                                                                                 |
| 403、`/problems/csrf-token-invalid`                                     | `apiFetch` が 1 回だけ再送し、失敗すれば `ApiProblemError`（`api-fetch.ts:87-89`）                                                                                                     | API の `csrf.ts:26-40` が 403 を返す（`commonProblemTypes` の `forbiddenOrigin` と `forbiddenCrossSiteRequest`）                           | 変えない                                                                                                 |
| 403、`about:blank`（権限不足）                                          | 管理者の画面だけ「管理者だけが使える画面です」（`members.tsx:11-28`）。ほかは「問題が発生しました」と「再試行」（`route-error.tsx:27-41`）                                             | 固定文言の 403 の画面。URL を変えず、リンクはトップ（`error-screens.tsx:65-73`）                                                           | 共通の 403 の画面。再試行を出さない（#646）                                                              |
| 404、`about:blank`（存在しない、見えない）                              | `notFound()` から `defaultNotFoundComponent`。注文の詳細は 404 と 400 を同じにする（`orders/$orderId/index.tsx:16-22`、`router-defaults.ts:14`）                                       | 存在しない URL も他人の申請も同じ 404 の画面（`api-error.ts:11-16`、`$.tsx:9-25`）                                                         | 変えない                                                                                                 |
| ログイン失敗（`/login/oauth2/code/web` の失敗）                         | 302 で `/login?error`。Spring が生成する英語の HTML（「Please sign in」）                                                                                                              | `/login?error=<識別子>` に、`role="alert"` の文言（`login-message.ts:30-38`、`login-screen.tsx:33`）                                       | `/login-failed`（#643）                                                                                  |

## 場面ごとの比較

| 場面                              | 向こう                                                                                                                           | こちら                                                                                                                      | 判定                                             |
| --------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| 未ログインで保護された URL を開く | `_authenticated` の `beforeLoad` が `GET /api/me` を呼び、401 なら `/login?redirect=<元の相対パス>`（`_authenticated.tsx:9-20`） | route は認証を確かめない。最初の query の 401 を `QueryCache` の `onError` が受けて IdP へ遷移する（`query-client.ts:7-9`） | 同じ目的。形は変えない                           |
| ログインの後の戻り先              | 検証した相対パスを `callbackURL` に渡す（`login.tsx:27-35`）                                                                     | 常に `/`（`TenantSelectionSuccessHandler.java:15-19`、`authentication-and-session.md:21`）                                  | 取り込む（#644）                                 |
| 戻り先の検査                      | `safeRedirect`（`login-redirect.ts:12-18`）                                                                                      | なし                                                                                                                        | 性質を取り込む。実装は URL API（#644）           |
| 操作の途中のセッション切れ        | 現在の URL を戻り先に、`reason=session-expired` で `/login` へ（`router.ts:16-23`）                                              | 同じ 401 の経路。理由を出さず、戻り先は `/`                                                                                 | 取り込む（#644、#645）                           |
| ログインの失敗                    | `/login` に識別子から選んだ文言を 1 枚出す。未知の識別子は「ログインできなかった」（`login-message.ts:30-38`）                   | 英語の既定の画面                                                                                                            | 取り込む（#643）。識別子は取り込まない           |
| ログアウト直後                    | `/login?reason=logged-out`「ログアウトしました。」                                                                               | `/logged-out`「ログアウトしました」と「もう一度ログイン」（`logged-out.tsx:5-24`）                                          | 同じ決定                                         |
| 403                               | 固定文言。必要な役割は書かない（`error-screens.tsx:4`）                                                                          | 管理者の画面だけ専用。役割を書く                                                                                            | 取り込む（#646）                                 |
| 404                               | 全 route で同じ画面。ログイン済みはメニューの中で「トップへ戻る」、未ログインは「ログインへ」（`error-screens.tsx:38-62`）       | 全 route で同じ画面。リンクは「ホームへ戻る」                                                                               | 同じ決定。メニューと「ログインへ」は取り込まない |
| 文言の出どころ                    | `type` から i18n のキーを引く（`problem.ts:8-23`）                                                                               | サーバーの `title` を見出しにし、説明は catalog（ADR-016、`notice.ts:12-16`）                                               | 取り込まない                                     |

## 取り込むもの

### ログインの失敗を SPA の画面で伝える（#643）

こちらの `oauth2Login` は失敗の遷移先を指定していない（`SecurityConfig.java:141-143`）。
Spring Security の既定は、ログインの URL に `?error` を付けた `/login?error` である（`AbstractAuthenticationFilterConfigurer` の `updateAuthenticationDefaults` と `failureUrl`、7.2.0 のソース）。
`/login` は `permitAll` の予約パス（`SecurityConfig.java:93`）で、`DefaultLoginPageGeneratingFilter` が英語の HTML を返す。
IdP がエラーを返した場合（`error=access_denied`）と、認可リクエストが見つからない場合（`state` が合わない、セッションが切れた）のどちらも 302 で `/login?error` に着き、本文は「Please sign in」と「Login with OAuth 2.0」とクライアントへのリンクだけだった（「確かめたこと」）。
`SignInOidcUserService` が ID トークンの `iss` と `sub` を拒否する場合（`SignInOidcUserService.java:62-66`）も同じ経路である。
利用者は、日本語の SPA から、何が起きたか分からない英語の画面へ放り出される。
アプリの INFO 以上のログに失敗の理由が出ない。
`backend/src` に `failureUrl` も `AuthenticationFailureHandler` もなく、Spring Security の filter は失敗を TRACE で記録する（7.2.0 の `AbstractAuthenticationProcessingFilter.java:423-425`）。

向こうは `/login` に識別子から選んだ文言を出す（向こうの #399）。
種類は、ログインできなかった、利用できないアカウント、一時的に使えない、セッション切れ、ログアウト直後である。
未知の識別子は「ログインできなかった」に倒す（`login-message.ts:35`）。
OWASP の Authentication Cheat Sheet がアカウントの有無を区別しない汎用のメッセージを求めることを、向こうは根拠にしている（向こうの #399 の決定）。

こちらで取り込むのは、種類を 1 つにした SPA の画面である。
`/logged-out` と同じく、リソースではなく状態を表す画面として `/login-failed` を足す。
`/login-failed` は `/login/**` の予約パスに当たらない（Vite の proxy の正規表現は `login(/|$)`、`vite.config.ts:161`）。
バックエンドは、`oauth2Login` の `failureHandler` に `SimpleUrlAuthenticationFailureHandler("/login-failed")` を包んだ handler を渡し、失敗の原因の `error` code を静的な event 名で INFO に残す（`docs/observability/log-levels.md:25`、`log-messages.md:10`）。
IdP が書く `error_description` と例外の message は、ログにも URL にも出さない。

向こうの「利用できないアカウント」と「一時的に使えない」は、こちらでは取り込まない。
向こうの前者は、人事システムで社員と確認できない人と退職者を拒む決定（向こうの #156）で、こちらはログインした人が所属を持たない状態を許し、招待を待つ画面を出す（`tenants-page.tsx:19-33`、`authentication-and-session.md:52`）。
後者は、人事システムの停止の識別子で、こちらに同じ依存がない。
識別子を URL に載せないので、履歴、ログ、Referer に失敗の種類が残らない。

### ログインの後に、ログインを求められた画面へ戻す（#644）

こちらの docs は、ログインの後は `/` に戻ると書く（`authentication-and-session.md:21`）。
バックエンドは要求を保存せず（`NullRequestCache`、`SecurityConfig.java:137`）、成功の handler は `/` へ戻す（`TenantSelectionSuccessHandler.java:15-19`、`:27-28`）。
保存しない理由は、既定の保存が `Accept` のない API の GET を保存し、ログインの後にその JSON へ戻すためである（`authentication-and-session.md:22-24`、#134 のコメント）。
その結果、`/orders/new` や `/orders/<id>` を開いていた利用者が、ログインの後に `/` へ着き、元の画面へ自分で戻ることになる。
戻り先を画面の URL にすることは、#134 のコメントが残した課題である。

向こうは、元の画面の URL を検証して `redirect` に載せ（`login-redirect.ts:36-43`）、ログインの `callbackURL` に渡す（`login.tsx:27-35`）。
TanStack Router のガイドも、`redirect: location.href` を search に載せ、ログインの後に `router.history.push(search.redirect)` で戻す形を示す（`authenticated-routes.md:39-45`、`:128-131`）。

こちらで戻り先を運ぶ方法を、3 つ比べた。

- **SPA が保持する**：401 を受けたとき、現在の path と query と hash を sessionStorage に 1 つだけ書いてから IdP へ移る。
  ログインの後に着く `/` の `beforeLoad` が読み、消して、その path へ移す。
- **バックエンドが保持する**：`/oauth2/authorization/web?redirect=…` を受ける resolver と、保存した値を読む成功の handler を足す。
- **Spring Security の `RequestCache`**：`OAuth2AuthorizationRequestRedirectFilter` が `RequestCache` を使うのは `ClientAuthorizationRequiredException` を受けたときだけである（7.2.0 のソース `OAuth2AuthorizationRequestRedirectFilter.java:205-213`）。
  画面の URL はバックエンドに届かないので、使えない。

SPA が保持する形を選ぶ。
バックエンドが保持する形は、resolver、session の属性、`TenantSelectionSuccessHandler` の変更が要り、バックエンドにも同じ検査が要る。
戻り先を使うのは SPA だけで、画面の URL を知るのも SPA だけである。
テナントを複数持つ利用者は、ログインの後に `/` から `/tenants` へ移り、選んだ後に文書を `/` で読み直す（`routing-and-state.md:74-75`）。
sessionStorage は同じ tab の文書の読み直しをまたいで残るので、`/` の `beforeLoad` で、テナントを選んだ後に読める。
値は 1 回読んだら消す。
`docs/frontend/browser-storage-and-cache.md:29` は、認証情報と個人情報を Web Storage に置かないと決める。
path と query は認証情報ではないが、`/logged-out` を開いたときにも消し、利用者をまたいで残さない（同 `:31`）。
sessionStorage が使えないときは、すでに使っている memory の fallback の形（`api-fetch.ts:111-130`）に合わせ、戻り先を諦めて `/` に戻す。

戻り先の検査は、向こうの `safeRedirect` の文字列の検査（`/` で始まる、`//` で始まらない、`\` と制御文字を含まない、`login-redirect.ts:12-18`）の代わりに、URL API を使う。
`new URL(value, location.origin)` で解決し、先頭が `/` で、解決した origin が `location.origin` と等しいときだけ通し、path、query、hash を作り直して使う。
守りたい性質は「同一オリジンの path」であり、ブラウザの URL の解析をそのまま使えば、`\` や tab、改行の扱いが解析と食い違わない。
向こうのテストの拒否する 14 の値のうち 12 は拒み、残りの `/a\b` と `/\u0000` は同一オリジンの path に解決されるので通る。
open redirect にはならない（「確かめたこと」）。

### 操作の途中のセッション切れを、ログインの前に伝える（#645）

向こうは、操作の途中の 401 で現在の URL を戻り先にして `/login?reason=session-expired` へ移し、「ログインの有効期限が切れました。もう一度ログインしてください。」を出す（`router.ts:16-23`、`ja/translation.json:17-20`）。
最初の訪問の 401 は、理由のない `/login` へ移す（`_authenticated.tsx:13-14`）。
向こうは、無操作の期限と最長の期限を区別しない（向こうの #399 の決定）。

こちらは、どちらの 401 も同じ経路で、画面を挟まずに IdP へ全画面で遷移する。
IdP の SSO セッションが生きていれば、IdP は資格情報を求めずに認可コードを返すので、利用者は画面を見ないまま `/` に着く（OIDC の通常の動きで、ブラウザでは確かめていない）。
更新（POST、PUT、PATCH、DELETE）で 401 を受けた利用者は、理由も、操作が完了していないことも知らされずに `/` に着く。
入力途中の内容を残すことは、向こうの決定でも範囲外なので、取り込まない。

こちらは向こうと違い、ログインを始める GET の URL がある。
最初の訪問（ブックマークから開く、朝の最初のアクセス）は、画面を挟まずに IdP へ移すほうが 1 クリック少ない。
そこで、画面を挟むのは、同じ文書で 1 回以上成功の応答を受けた後の 401 だけにする。
成功の後の 401 は、ログインしていた利用者のセッションが切れたことを意味する。
最初の 401 は、ログインしていなかったことを意味する。

画面は `/logged-out` と同じ形の `/session-expired` とし、「ログインの有効期限が切れました。もう一度ログインしてください。」と、操作が完了していない可能性を伝える。
ボタンは `/oauth2/authorization/web` へのリンクにし、戻り先は #644 の仕組みで運ぶ。

サーバーが 401 の type で区別する形（向こうの `unauthenticated` と `session-expired`、API の `middleware/session.ts:40-65`）は取り込まない。
こちらの 401 は `about:blank` で、#309 で type を付けるのは 422 と 409 だけと決めた。
サーバーが「セッションの Cookie はあるが無効」を見分けても、利用者の次の訪問も同じ判定になり、直接 IdP へ移したい最初の訪問と合わない。
区別したいのは、同じ文書で成功を見たかどうかで、これはクライアントだけが知る。

30 秒以内の 2 回目は遷移しない guard（`api-fetch.ts:107-145`）は、最初の 401 が IdP へ直接移るので、残す。
guard が止める 401 の繰り返しは、ログインの後も 401 が続く不具合のときの無限の遷移である。

### 権限不足の 403 を、再試行を出さない画面で伝える（#646）

こちらで権限不足の 403 を返すのは、今は `/api/current-tenant/members` の配下だけである（`TenantSecurityCustomizer.java:38-40`、MockMvc で member が 403 `about:blank`「アクセス権限がありません」を受けたことを確かめた）。
その画面だけが、専用の見出し「管理者だけが使える画面です」を出す（`members.tsx:11-28`）。
ほかの権限不足の 403 は、`RouteError` が一般の「問題が発生しました」と「再試行」を出し、テストがその形を固定している（`tenants-page.test.tsx:122-129`）。
再試行しても結果は変わらない。4xx は自動の再試行もしない（`retryUnlessClientError`、`api-fetch.ts:151-162`）ので、ボタンを押すと同じ 403 の画面に戻るだけである。

役割で許す操作は、#315 の決定どおり `@PreAuthorize` で 403 にする（#568）。
招待（#380）も、管理者だけの操作になる。
権限不足の 403 を返す画面は増えるので、画面ごとの専用の画面ではなく、`RouteError` の中の共通の 403 の画面にする。

向こうの 403 の画面は、固定の 1 文「このページを表示する権限がありません。」と「トップへ戻る」だけで、必要な役割は書かない（`error-screens.tsx:4`、`:65-73`、向こうの #399）。
URL は変えず、権限のない route の中に出す。
こちらも同じ形にし、`MembersError` と、その専用のキー（`tenancy.members.forbiddenHeading`、`forbiddenDescription`）を消す。
必要な役割を書かないのは、役割の構成（#316 の `admin` と `member`）が変わっても文言を直さないためで、役割の名前が秘密だからではない（エンドポイントの存在は API 文書で公開している、`status-codes.md:66`）。
文言の最終の案は作業の Issue で決める。

向こうの `_accounting` の guard（`GET /api/me` の `roles` を見て `beforeLoad` で 403 を投げる、`_accounting.tsx:9-15`）は取り込まない。
こちらの `MembershipResponse` は役割を返さず（`openapi.yaml:601-616`）、SPA は利用者の役割を知らない。
役割を API に足し、リンクを出し分けるかは、管理者の画面が増える #380 で決める。
`docs/frontend/authorization-ui.md:23` は、すでに共通の親 route の `beforeLoad` で判定することを決めている。

## 取り込まないもの

- **SPA の `/login` の画面と、`GET /api/me` による route の guard**（`login.tsx:9-25`、`_authenticated.tsx:9-20`）：こちらの `/login/**` は ADR-014 でバックエンドの予約パスで、IdP のログインの開始は GET の URL にある。
  最初の訪問に 1 クリックと 1 回の API 呼び出しが増え、得るのは向こうの構造の揃えだけである。
  最初の訪問は、今の経路が画面を挟まずに IdP へ移す。
- **ログイン失敗の識別子と、利用できないアカウントの文言**（`login-message.ts:15-17`）：上の「ログインの失敗」のとおり、人事システムの確認がなく、識別子を URL に載せる費用に見合わない。
- **`type` による 401 の区別と、`type` から文言を引く `problemMessage`**（`problem.ts:8-23`）：#309 の決定に合う。
  こちらは Problem Details の `title` をサーバーが日本語と英語にし（ADR-016）、SPA は `title` を見出しに使う（`notice.ts:12-16`）。
- **未ログインの 404 の「ログインへ」と、ログイン済みの 404 をメニューの中に出す形**（`error-screens.tsx:38-62`、`$.tsx:9-25`）：向こうは `GET /api/me` でログイン済みかを確かめて、2 つの画面を分ける。
  こちらは未知の URL でセッションを確かめる API を持たず、404 のためだけに足すと、404 の画面が API の失敗に左右される（向こうはそれを避けるために失敗を未ログインに倒す、`$.tsx:11`）。
  「ホームへ戻る」は、ログイン済みならホーム、未ログインなら IdP へ着き、どちらの利用者にも通る。
  TanStack Router の既定の `fuzzy` は、未知の子の URL（`/orders/x/y`）を最も近い一致する route で処理するので、`AppShell` の中に出る。
  最上位の未知の URL（`/foo`）は `AppShell` の外に出る。
  この違いは画面では確かめていない。
- **`/login` のログアウト直後の文言**（`login-screen.tsx`、`ja/translation.json:19`）：`/logged-out` が同じ役割を持つ（`logged-out.tsx:5-24`）。
- **向こうの `Clear-Site-Data` と最長のセッションの期限**：#345 で扱う。

## コメントへの回答

この Issue（#336）のコメントは 2 つある。

- 追加で見るもの（`api-error.ts`、`forbidden.ts`、`login-redirect.ts`、`components/error-screens.tsx`）：すべて読んだ。
  `api-error.ts` と `forbidden.ts` は、loader が受けた API の 403 と 404 を画面の失敗へ変える部品で、こちらは route の `errorComponent` と `notFound()` がすでに同じ役割を持つ（`orders/$orderId/index.tsx:16-22`）。
  取り込むのは `error-screens.tsx` の 403 の文言と形（#646）と、`login-redirect.ts` の戻り先の性質（#644）である。
- `login-redirect.ts` の追記（同一オリジンの相対パスに限り、`/\evil.example` も止める。`router.ts` が、操作の途中のセッション切れで今の画面を戻り先にして `/login` に移す）：#644 と #645 に取り込んだ。
  検査の実装は URL API にした。

## ほかの Issue との関係

- **#316、#315、#309（整合を求められた決定）**
  - #316：テナントと役割はアプリの DB にあり、IdP は認証だけである。
    #646 は、SPA が役割を持たない前提（`MembershipResponse` に役割がない）のまま、403 の画面を共通にする。
  - #315：見えないものは 404、見えるが許されない操作は 403 である。
    #646 は 403 を「権限がない」の 1 つの画面にし、404 の画面は変えない。
    他人のリソースが 404 になることは #315 の BOLA のテスト（#568）が確かめる。
  - #309：401、403、404 の type は `about:blank` のままで、type を付けるのは 422 と 409 だけである。
    #645 は 401 の type を足さず、クライアントの状態で区別する。
- **#134**：401 で IdP へ遷移する処理の Issue（まだ開いている）。
  #644 と #645 は、同じ `redirectToLoginOnUnauthorized` を変える。
  #134 のコメントが残した「戻り先を画面の URL にする」を、#644 で決める。
  #134 の変更が先に入るときは、その上に載せる。
- **#345**：セッションの期限、CSRF、ログアウトの後始末。
  #645 はセッションの期限の値を決めない。
- **#380**：招待は管理者だけの操作で、#646 の 403 の画面を使う。
  役割を API に足してリンクを出し分けるかは #380 で決める。
- **#568**：`@PreAuthorize` の 403 が増える。
  #646 の画面は、その 403 を受ける。
- **#203**：操作の通知（409 と 422 以外）で再試行を促さない。
  #646 は画面の 403 で、#203 の操作の通知は扱わない。
- **#590、#635**：401 と 403 のログの属性。
  #643 の失敗のログは、別の event 名で足す。
- **ADR-014、ADR-059**：変えない。
  `/login-failed` と `/session-expired` は `/login/**` に当たらない。
  ADR-059 の「401 を受けたらログインへ遷移する」は、#645 の後も成り立つ。
- **ADR は起こさない**：画面と文言の決定で、戻すのが簡単である。
  向こうも、#399 の決定に ADR を書いていない。

## 限界

- サーバーの応答は MockMvc で確かめた。
  本物のブラウザと IdP の組では確かめていない。
  Keycloak と本番の Cognito が失敗のときに返す `error` の値と、SSO セッションが生きているときの遷移は確かめていない。
- 配信点（リバースプロキシ、CDN）が `/login-failed` と `/session-expired` を SPA へ振り分けることは確かめていない。
  リポジトリの `infrastructure/` と `docker/` に、`/login` の振り分けの設定は見つからなかった。
  ADR-014 の「パスで振り分ける」と、Vite の proxy の正規表現だけを読んだ。
  配信点が `/login*` のように前方一致で振り分けると、`/login-failed` がバックエンドへ行く。
- sessionStorage が IdP への遷移をまたいで残ることは、ブラウザで確かめていない。
  HTML の仕様どおり、同じ tab（同じ browsing context）では残る前提である。
- 向こうの画面は、story と unit のテストの記述を読んだだけで、実行していない。
- こちらのフロントエンドのテストは、依存を入れていないので実行していない。
  404 が `AppShell` の中に出るかは、route の構成と TanStack Router のガイドから読んだ。
- TanStack Router のリリースノートの全件は読んでいない。

## 確かめたこと

- こちらの `origin/main`（`ee8bf60`）で、`SecurityConfig` の本物の filter chain を、一時の MockMvc のテスト（commit しない）で通した（`SharedTestConfiguration` のテスト用の IdP の登録、テナントを選んだ MEMBER は `AppUsers.oidcLogin()`）。
  - 未認証の `GET /api/orders` と `GET /api/missing`：401、`application/problem+json`、`WWW-Authenticate: Session realm="demo"`、`type` は `about:blank`、`title` は「認証が必要です」。
    存在しない path でも、認証が先に 401 になる。
  - 未認証の `GET /orders`（`Accept: text/html`）：302 で `/oauth2/authorization/web`。
  - 未認証の `GET /login`：200、HTML、`<title>Please sign in</title>`、「Login with OAuth 2.0」。
  - テナントを選んだ MEMBER の `GET /api/missing` と `GET /api/orders/<存在しない UUID>`：404、`about:blank`、「リソースが見つかりません」。
  - 同じ MEMBER の `GET /api/current-tenant/members`：403、`about:blank`、「アクセス権限がありません」。
  - テナントを選んでいない利用者の `GET /api/orders`：403、`/problems/tenant-not-selected`、「テナントが選ばれていません」。
  - `GET /oauth2/authorization/web` で認可リクエストを作り、IdP のエラー（`error=access_denied`、`error_description` つき）をコールバックに返すと、302 で `/login?error`。
    同じ session で `/login?error` を開くと、200 の「Please sign in」の HTML で、エラーの文は出ない。
  - 認可リクエストのない session でコールバックを開く（`code` と `state` だけ）と、302 で `/login?error`。
- Spring Security の最新は 7.2.0、こちらは 7.1.2（#315 の調査と同じ）である。
  7.1.2 と 7.2.0 のソースで、`AbstractAuthenticationFilterConfigurer` と `OAuth2LoginConfigurer` は同じで、`failureUrl` と `failureHandler` は `oauth2Login` で使え、既定は `loginPage + "?error"` である。
  `OAuth2AuthorizationRequestRedirectFilter` の `RequestCache` は `ClientAuthorizationRequiredException` の経路だけで使う。
  7.1.2 と 7.2.0 で同じである。
  7.1 と 7.2 の What's New（`whats-new.adoc`、22 行と 16 行）に、`oauth2Login` の失敗、戻り先、`RequestCache` の項目はない。
  判断を変える機能はないので、上げることは要らない。
- TanStack Router の最新は 1.170.46、こちらは 1.170.38 である。
  `authenticated-routes.md` に、`redirect: location.href` を search に載せ、ログインの後に `router.history.push(search.redirect)` で戻す形がある。
  `not-found-errors.md` の `notFoundMode` の既定は `fuzzy` である（TanStack/router の `ce46b21`）。
- 戻り先の検査（Node 26.13.1、`new URL(value, origin)` で解決し、先頭が `/` で origin が等しいときだけ通す）に、向こうの `login-redirect.test.ts:9-34` の値を当てた。
  通す 4 つ（`/`、`/claims`、`/claims/1?tab=a#top`、`/a//b`）はすべて通った。
  拒む 14 のうち、`//evil.example`、`//evil.example/path`、`https://…`、`http://…`、`javascript:…`、`/\evil.example`、`\\evil.example`、`/\t/evil.example`、`/\n/evil.example`、`claims`、空文字、先頭に空白のある `/claims` の 12 を拒んだ。
  `/a\b`（`/a/b`）と `/\u0000`（`/%00`）は通った。
  どちらも同一オリジンの path である。
- 向こうの `main` は `3ba2640`。
  向こうの PR #541（`31ab0ea`）と #564（`302cd89`）は merge 済みである。
- こちらで `/login-failed` と `/session-expired` を足すと、`routeTree.gen.ts` の `fullPaths` が変わり、`docker/otel-collector/config.yaml:56` の allowlist と `docs/observability/conventions.md:159` を同じ変更で直す必要がある（`task fe-route-tree-check`、`task otel-collector-check`、`conventions.md:161`）。

## 出典

- 向こう：上の「向こう」に挙げたファイル（commit `3ba2640`）、向こうの Issue #397、#399、#406、#415、向こうの PR #541、#564
- こちら：上の「こちら」に挙げたファイル（commit `ee8bf60`）、Issue #134、#203、#305、#309、#315、#316、#345、#380
- [Spring Security 7.2.0 のソース](https://github.com/spring-projects/spring-security/tree/7.2.0)：`AbstractAuthenticationFilterConfigurer`、`OAuth2LoginConfigurer`、`OAuth2AuthorizationRequestRedirectFilter`、`docs/modules/ROOT/pages/whats-new.adoc`（7.1.0 と 7.2.0）
- [TanStack Router: Authenticated Routes](https://tanstack.com/router/latest/docs/guide/authenticated-routes)、[Not Found Errors](https://tanstack.com/router/latest/docs/guide/not-found-errors)
- [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)（向こうの #399 が根拠にした。こちらでは原文を読んでいない）
- [WHATWG URL Standard](https://url.spec.whatwg.org/)（特別なスキームで `\` を `/` として扱い、tab と改行を取り除く）

## 作業の Issue

- #643：ログインの失敗を、英語の既定の画面ではなく SPA の画面で伝える。
- #644：ログインの後に、ログインを求められた画面へ戻す。
- #645：操作の途中でセッションが切れたことを、ログインの前に画面で伝える。
- #646：権限不足の 403 を、再試行を出さない画面で伝える。
