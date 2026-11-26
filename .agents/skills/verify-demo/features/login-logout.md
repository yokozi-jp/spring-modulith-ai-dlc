# ログインとログアウト

利用者は Keycloak の画面で `test-user` としてログインし、トップページ（見出し「はじめる」）へ戻る。
ヘッダーの「ログアウト」を押すと、アプリのセッションと Keycloak のセッションが終わり、「ログアウトしました」の画面に着く。

## Sub-features

- `auth-login`：`/oauth2/authorization/web` から Keycloak の画面へ移り、資格情報を送り、テナント選択でサンプル商事を選ぶと `webOrigin`（slot 0 は `http://localhost:5173`）の `/` へ戻る。
- `auth-session`：ログインの前後で `__Host-APP_SESSION` の Cookie の値が変わり、`/api/missing` が 401 でなく 404 を返す。
- `auth-logout`：「ログアウト」で「ログアウトしました」の画面に着き、古い `__Host-APP_SESSION` で `/api/**` を呼ぶと 401 になる。
- `auth-login-again`：ログアウト後の「もう一度ログイン」から、再び Keycloak の画面へ移る。
- `auth-login-failed`：ログインのコールバックが失敗すると、`/login-failed` の「ログインできませんでした」の画面に着く。URL に失敗の種類が載らず、英語の「Please sign in」が出ない。「もう一度ログイン」から Keycloak の画面へ移る。backend のログに `OAuth2 login failed` と `error.type` が残り、IdP が書いた説明が残らない。
- `auth-session-expired`：同じ文書で API が成功した後にセッションが切れ、次の API が 401 を返すと、Keycloak の画面ではなく `/session-expired` の「ログインの有効期限が切れました」の画面に着く。「もう一度ログイン」から Keycloak の画面へ移る。この sub-feature は `/verify-demo` の journey を持たず、`frontend/apps/web/e2e/session-expired.spec.ts` だけが確かめる。

## How to get to it (user POV)

- ブラウザで `/oauth2/authorization/web` を開く（ログインの入口）。
- 認証済みの画面のヘッダーの「ログアウト」ボタンを押す。
- 「ログアウトしました」の画面の「もう一度ログイン」を押す。
- IdP が拒否して戻ってくる `/login/oauth2/code/web?error=...` を開く（ログインの失敗）。

## Driving it with drive.mjs

Preconditions:

- `stack.sh doctor` がすべて OK である。
- journey は `export const login = false` を宣言し、自分で `login()` を呼ぶ。
  自動のログインの前の状態を残すためである。

`node .kiro/skills/verify-demo/scripts/drive.mjs .kiro/skills/verify-demo/journeys/login-logout.mjs` が、次のすべてを順に実行する。

- **ログイン（`auth-login`、`auth-session`）。**
  `page.goto("/oauth2/authorization/web")` の後、`expect(page).toHaveURL((url) => url.origin === idpOrigin)` で Keycloak の画面を確かめて `step` する。
  `context.cookies()` の `__Host-APP_SESSION` を覚えてから `login()` を呼ぶ。
  URL が `webOrigin` の `/` になり、`main` の `heading`（level 1、「はじめる」）が見え、Cookie の値が変わる。
  `page.request.get("/api/missing")` が 404 を返す。
- **ログアウト（`auth-logout`）。**
  `page.getByRole("button", { name: "ログアウト" }).click()` で押す。
  `heading`「ログアウトしました」が見える。
  `const fresh = await request.newContext({ baseURL: webOrigin })` で Cookie を共有しない要求を作る。
  ログアウトの前の `__Host-APP_SESSION` を `headers: { Cookie: \`__Host-APP_SESSION=${session}\` }` に付けて `fresh.get("/api/missing")` を呼ぶと、401 を返す。
  最後に `fresh.dispose()` を呼ぶ。
- **もう一度ログイン（`auth-login-again`）。**
  「もう一度ログイン」を押す。
  URL の origin が `idpOrigin` になり、Keycloak の `Username or email` の欄が見える。

`node .kiro/skills/verify-demo/scripts/drive.mjs .kiro/skills/verify-demo/journeys/login-failed.mjs` が、次を順に実行する（`auth-login-failed`）。
`journeys/login-failed.mjs` も `export const login = false` を宣言する。

- **IdP が拒否した場合。**
  `/oauth2/authorization/web` から Keycloak の画面へ移り、URL の `state` を取る。
  Keycloak が拒否したときと同じ `error=access_denied` と、`error_description` に `uniqueCode` の目印を付けた query で、`webOrigin` の `/login/oauth2/code/web` を開く。
  URL が `webOrigin` の `/login-failed`（query なし）になり、`heading`「ログインできませんでした」が見え、「Please sign in」が見えない。
- **認可リクエストがない場合。**
  同じ page で `/login/oauth2/code/web?code=x&state=y` を開くと、同じ画面に着く。
- **もう一度ログイン。**
  「もう一度ログイン」を押すと、URL の origin が `idpOrigin` になる。
- **ログ。**
  `stack.sh down` が書く `build/verify/logs/` の backend のログに、`OAuth2 login failed` の `error.type` の `access_denied` と `authorization_request_not_found` があり、目印の文字列がない。

## Gotchas

- ログインの証拠の取り方と、未認証の `/api/**` を同じ context で呼んではいけない理由は、`docs/e2e/testing-strategy.md` の「認証」と `frontend/apps/web/e2e/logout.spec.ts` にある。
  401 の確認は、必ず `request.newContext` で作った要求で行う。
  `page.request` と `context.request` は Cookie を共有する。
- 未認証で `/` を開くと、`_tenant` の `beforeLoad` が呼ぶ `GET /api/v1/current-tenant` の 401 で、SPA が `/oauth2/authorization/web` へ移る。
  同じ tab で 30 秒以内に 2 回目の 401 を受けると移らず、`RouteError` を表示する（`@frontend/api/unauthorized-redirect` の `createUnauthorizedRedirect`）。
  続けて別の画面を未認証で開くときは、`context.newPage()` で tab を分ける。
  この遷移は `frontend/apps/web/e2e/logout.spec.ts` と `unauthenticated-redirect.spec.ts` が固定する。
- Keycloak の issuer は `idpOrigin`（slot 0 は `http://127.0.0.1:8081`）に固定されている。
  `localhost` で開くと issuer が一致せず失敗する。
- compose-test には Collector がない。
  `drive.mjs` が `/collect` を 202 で返すので、ログアウトのフォームの送信は止まらない。
