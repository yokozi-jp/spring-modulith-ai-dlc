# ログインとログアウト

利用者は Keycloak の画面で `test-user` としてログインし、トップページ（見出し「はじめる」）へ戻る。
ヘッダーの「ログアウト」を押すと、アプリのセッションと Keycloak のセッションが終わり、「ログアウトしました」の画面に着く。

## Sub-features

- `auth-login`：`/oauth2/authorization/web` から Keycloak の画面へ移り、資格情報を送ると `http://localhost:5173/` へ戻る。
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
- journey は `export const login = false` を宣言し、自分で `login()` を呼ぶ（自動のログインの前の状態を残すため）。

- **ログイン（`auth-login`、`auth-session`）。** `page.goto("/oauth2/authorization/web")` の後、`expect(page).toHaveURL(/^http:\/\/127\.0\.0\.1:8081\//u)` で Keycloak の画面を確かめて `step` する。`context.cookies()` の `APP_SESSION` を覚えてから `login()` を呼ぶ。URL が `http://localhost:5173/` になり、`main` の `heading`（level 1、「はじめる」）が見え、Cookie の値が変わる。`page.request.get("/api/missing")` が 404 を返す。
- **ログアウト（`auth-logout`）。** `page.getByRole("button", { name: "ログアウト" }).click()` を押す。`heading`「ログアウトしました」が見える。ログアウトの前の `APP_SESSION` を `Cookie` の header に付けて、別の `APIRequestContext`（`playwright.request.newContext()`）で `/api/missing` を呼ぶと 401 を返す。
- **もう一度ログイン（`auth-login-again`）。** 「もう一度ログイン」を押す。URL が `http://127.0.0.1:8081/` で始まり、Keycloak の `Username or email` の欄が見える。

## Gotchas

- `APP_SESSION` は認証の開始の時点で発行される。Cookie があることだけをログインの証拠にせず、値の変化と `/api/missing` の 404 で確かめる。
- `/` は SPA が認証を確かめずに表示する。トップページが見えることだけをログイン済みの証拠にしない。
- 未認証の `/api/**` を同じ browser context で呼ぶと、その要求がセッションに保存され、次のログイン後にそこへ戻される。401 の確認は Cookie を共有しない request で行う。
- Keycloak の issuer は `http://127.0.0.1:8081` に固定されている。`localhost:8081` を開くと issuer が一致せず失敗する。
- compose-test には Collector がない。`drive.mjs` が `/collect` を 202 で返すので、ログアウトのフォームの送信は止まらない。
