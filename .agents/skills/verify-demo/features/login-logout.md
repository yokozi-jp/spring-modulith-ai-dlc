# ログインとログアウト

利用者は Keycloak の画面で `test-user` としてログインし、トップページ（見出し「はじめる」）へ戻る。
ヘッダーの「ログアウト」を押すと、アプリのセッションと Keycloak のセッションが終わり、「ログアウトしました」の画面に着く。

## Sub-features

- `auth-login`：`/oauth2/authorization/web` から Keycloak の画面へ移り、資格情報を送り、テナント選択でサンプル商事を選ぶと `webOrigin`（slot 0 は `http://localhost:5173`）の `/` へ戻る。
- `auth-session`：ログインの前後で `APP_SESSION` の Cookie の値が変わり、`/api/missing` が 401 でなく 404 を返す。
- `auth-logout`：「ログアウト」で「ログアウトしました」の画面に着き、古い `APP_SESSION` で `/api/**` を呼ぶと 401 になる。
- `auth-login-again`：ログアウト後の「もう一度ログイン」から、再び Keycloak の画面へ移る。

## How to get to it (user POV)

- ブラウザで `/oauth2/authorization/web` を開く（ログインの入口）。
- 認証済みの画面のヘッダーの「ログアウト」ボタンを押す。
- 「ログアウトしました」の画面の「もう一度ログイン」を押す。

## Driving it with drive.mjs

Preconditions:

- `stack.sh doctor` がすべて OK である。
- journey は `export const login = false` を宣言し、自分で `login()` を呼ぶ。
  自動のログインの前の状態を残すためである。

`node .kiro/skills/verify-demo/scripts/drive.mjs .kiro/skills/verify-demo/journeys/login-logout.mjs` が、次のすべてを順に実行する。

- **ログイン（`auth-login`、`auth-session`）。**
  `page.goto("/oauth2/authorization/web")` の後、`expect(page).toHaveURL((url) => url.origin === idpOrigin)` で Keycloak の画面を確かめて `step` する。
  `context.cookies()` の `APP_SESSION` を覚えてから `login()` を呼ぶ。
  URL が `webOrigin` の `/` になり、`main` の `heading`（level 1、「はじめる」）が見え、Cookie の値が変わる。
  `page.request.get("/api/missing")` が 404 を返す。
- **ログアウト（`auth-logout`）。**
  `page.getByRole("button", { name: "ログアウト" }).click()` で押す。
  `heading`「ログアウトしました」が見える。
  `const fresh = await request.newContext({ baseURL: webOrigin })` で Cookie を共有しない要求を作る。
  ログアウトの前の `APP_SESSION` を `headers: { Cookie: \`APP_SESSION=${session}\` }` に付けて `fresh.get("/api/missing")` を呼ぶと、401 を返す。
  最後に `fresh.dispose()` を呼ぶ。
- **もう一度ログイン（`auth-login-again`）。**
  「もう一度ログイン」を押す。
  URL の origin が `idpOrigin` になり、Keycloak の `Username or email` の欄が見える。

## Gotchas

- ログインの証拠の取り方と、未認証の `/api/**` を同じ context で呼んではいけない理由は、`docs/e2e/testing-strategy.md` の「認証」と `frontend/apps/web/e2e/logout.spec.ts` にある。
  401 の確認は、必ず `request.newContext` で作った要求で行う。
  `page.request` と `context.request` は Cookie を共有する。
- `/` は SPA が認証を確かめずに表示する。
  トップページが見えることだけをログイン済みの証拠にしない。
- Keycloak の issuer は `idpOrigin`（slot 0 は `http://127.0.0.1:8081`）に固定されている。
  `localhost` で開くと issuer が一致せず失敗する。
- compose-test には Collector がない。
  `drive.mjs` が `/collect` を 202 で返すので、ログアウトのフォームの送信は止まらない。
