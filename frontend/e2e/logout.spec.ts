/* oxlint-disable eslint/max-statements, eslint/max-lines, unicorn/no-array-callback-reference -- セッション前後の公開 wire 契約を一つの利用者フローで検査する。 */
import type { APIRequestContext, BrowserContext, Cookie, Locator, Page } from "@playwright/test";

import { signInOnKeycloak } from "./environment";
import { expect, test } from "./fixtures";

// 共有の storageState のセッションを終わらせないよう、各テストは専用の context でログインする。
test.use({ storageState: { cookies: [], origins: [] } });

// compose-test が Keycloak の issuer を固定している（auth.setup.ts と同じ）。
const endSessionEndpoint =
  "http://127.0.0.1:8081/realms/spring-modulith/protocol/openid-connect/logout?";

const logoutButton = (page: Page): Locator => page.getByRole("button", { name: "ログアウト" });
const homeHeading = (page: Page): Locator =>
  page.getByRole("main").getByRole("heading", { level: 1, name: "はじめる" });
// hidden input は role を持たないため、規約の例外として locator で取る。
const csrfInput = (page: Page): Locator => page.locator('input[name="_csrf"]');

async function logIn(page: Page): Promise<void> {
  await page.goto("/oauth2/authorization/web");
  await signInOnKeycloak(page);
  await expect(page).toHaveURL("http://localhost:5173/");
}

async function apiStatus(page: Page): Promise<number> {
  const response = await page.request.get("/api/missing");
  return response.status();
}

// / は SPA が認証を確かめずに表示するため、/api/missing が 404（401 でない）であることで認証済みを確かめる。
async function expectAuthenticatedHome(page: Page): Promise<void> {
  await expect(page).toHaveURL("http://localhost:5173/");
  await expect(homeHeading(page)).toBeVisible();
  expect(await apiStatus(page)).toBe(404);
}

// 未認証の /api/** を browser の context で呼ぶと、Spring Security がその要求を新しいセッションに保存し、
// 次のログイン後に /api/missing?continue へ戻してしまう。そのため 401 は Cookie を共有しない request で確かめる。
async function replayedApiStatus(request: APIRequestContext, session: string): Promise<number> {
  const response = await request.get("/api/missing", {
    headers: { Cookie: `APP_SESSION=${session}` },
  });
  return response.status();
}

async function expectApplicationSessionEnded(
  context: BrowserContext,
  request: APIRequestContext,
  oldSession: string,
): Promise<void> {
  // ログアウトの応答は APP_SESSION を消す。
  const cookies = await context.cookies();
  expect(cookies.map(({ name }) => name)).not.toContain("APP_SESSION");
  // ログアウト前の APP_SESSION を送り直しても、サーバ側のセッションが消えていれば 401 になる。
  expect(await replayedApiStatus(request, oldSession)).toBe(401);
}

async function expectLoggedOutPage(page: Page): Promise<void> {
  await expect(page).toHaveURL("http://localhost:5173/logged-out");
  await expect(page.getByRole("heading", { level: 1, name: "ログアウトしました" })).toBeVisible();
}

// 改変したフォームで送ると 403 になり、ログアウトせずに認証済みのまま残ることを確かめる。
// 改変が送信に届いたことを呼び出し側で確かめられるよう、送った _csrf の値を返す。
async function expectLogoutRejected(page: Page): Promise<string | null> {
  const logoutResponse = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" && new URL(response.url()).pathname === "/logout",
  );
  await logoutButton(page).click();
  const response = await logoutResponse;
  expect(response.status()).toBe(403);
  const submittedToken = new URLSearchParams(response.request().postData() ?? "").get("_csrf");

  await page.goto("/");
  await expectAuthenticatedHome(page);
  return submittedToken;
}

// Cookie がないまま undefined を送り、別の理由で失敗するのを防ぐ。
async function findCookie(context: BrowserContext, name: string): Promise<Cookie> {
  const cookies = await context.cookies();
  const cookie = cookies.find((candidate) => candidate.name === name);
  if (cookie === undefined || cookie.value === "") {
    throw new Error(`${name} Cookie がありません`);
  }
  return cookie;
}

async function cookieValue(context: BrowserContext, name: string): Promise<string> {
  const { value } = await findCookie(context, name);
  return value;
}

async function waitForTelemetry(payloads: string[], value: string): Promise<void> {
  await expect
    .poll(() => payloads.some((payload) => payload.includes(value)), { timeout: 15_000 })
    .toBe(true);
}

function sessionId(payload: string): string {
  const match = /"session":\{[^}]*"id":"(?<id>[^"]+)"/u.exec(payload);
  const id = match?.groups?.id;
  if (typeof id !== "string" || id === "") {
    throw new Error("Faro payload に session ID がありません");
  }
  return id;
}

async function throwBrowserError(page: Page, message: string): Promise<void> {
  await page.evaluate((errorMessage) => {
    setTimeout(() => {
      throw new Error(errorMessage);
    }, 0);
  }, message);
}

let cspMessages: string[] = [];

test.beforeEach(({ page }) => {
  cspMessages = [];
  // ログアウトのフォームは IdP へ redirect されるため、form-action の違反は console に出る。
  // Keycloak の画面のメッセージも区別せずに集め、隠さない。
  page.on("console", (message) => {
    if (message.text().includes("Content Security Policy")) {
      cspMessages.push(message.text());
    }
  });
});

test.afterEach(() => {
  expect(cspMessages).toEqual([]);
});

test("ログイン直後に、ほかの操作をせずにログアウトすると /logged-out へ移る", async ({ page }) => {
  await logIn(page);

  const endSession = page.waitForRequest((request) => request.url().startsWith(endSessionEndpoint));
  await logoutButton(page).click();
  const request = await endSession;

  expect(new URL(request.url()).searchParams.get("post_logout_redirect_uri")).toBe(
    "http://localhost:5173/logged-out",
  );
  await expectLoggedOutPage(page);
  await expect(page).toHaveTitle("デモアプリケーション");
});

test("例外、画面遷移、Web Vitals をログアウト前後の匿名 session で相関できる", async ({ page }) => {
  const payloads: string[] = [];
  await page.route("**/collect", async (route) => {
    payloads.push(route.request().postData() ?? "");
    await route.fulfill({ status: 202 });
  });

  await logIn(page);
  await waitForTelemetry(payloads, '"view_changed"');
  await page.getByText("デモアプリケーション").click();
  await page.waitForTimeout(600);
  await page.evaluate(() => {
    const element = document.createElement("div");
    element.style.height = "100px";
    document.querySelector("main")?.prepend(element);
  });
  await throwBrowserError(page, "old-session-error");
  await waitForTelemetry(payloads, "old-session-error");
  await page.goto("/?token=secret-query#secret-fragment");
  await expect(homeHeading(page)).toBeVisible();
  await waitForTelemetry(payloads, '"lcp"');
  await waitForTelemetry(payloads, '"inp"');
  await waitForTelemetry(payloads, '"cls"');

  const oldPayloads = payloads.filter((payload) =>
    ["old-session-error", '"view_changed"', '"lcp"', '"inp"', '"cls"'].some((value) =>
      payload.includes(value),
    ),
  );
  const oldSession = sessionId(
    oldPayloads.find((payload) => payload.includes("old-session-error")) ?? "",
  );
  expect(new Set(oldPayloads.map(sessionId))).toStrictEqual(new Set([oldSession]));

  await logoutButton(page).click();
  await expectLoggedOutPage(page);
  await waitForTelemetry(payloads, '"toView":"/logged-out"');
  const loggedOutPayload =
    payloads.find((payload) => payload.includes('"toView":"/logged-out"')) ?? "";
  const newSession = sessionId(loggedOutPayload);
  expect(newSession).not.toBe(oldSession);

  await page.getByRole("link", { name: "もう一度ログイン" }).click();
  await signInOnKeycloak(page);
  await expectAuthenticatedHome(page);
  await throwBrowserError(page, "new-session-error");
  await waitForTelemetry(payloads, "new-session-error");
  const newErrorPayload = payloads.find((payload) => payload.includes("new-session-error")) ?? "";
  expect(sessionId(newErrorPayload)).toBe(newSession);

  for (const payload of payloads) {
    const itemCount = ["exceptions", "events", "measurements"].filter((key) =>
      new RegExp(`"${key}":\\[[^\\]]`, "u").test(payload),
    ).length;
    expect(itemCount).toBeLessThanOrEqual(1);
  }
  expect(payloads.join("\n")).not.toMatch(
    /secret-query|secret-fragment|APP_SESSION|person@example\.com|isSampled|unknown|#secret-selector/u,
  );
});

test("ログアウト後はアプリと SSO のセッションが終わり、もう一度ログインして戻れる", async ({
  page,
  context,
  request,
}) => {
  await logIn(page);
  const oldSession = await cookieValue(context, "APP_SESSION");
  // 再送の経路が Cookie を届けていることを、ログアウト前に同じ経路で示す。
  expect(await replayedApiStatus(request, oldSession)).toBe(404);
  await logoutButton(page).click();
  await expectLoggedOutPage(page);
  await expectApplicationSessionEnded(context, request, oldSession);

  // Keycloak の SSO セッションが終わっていれば、資格情報の入力を求められる。
  await page.getByRole("link", { name: "もう一度ログイン" }).click();
  await expect(page.getByLabel("Username or email")).toBeVisible();
  await signInOnKeycloak(page);

  await expectAuthenticatedHome(page);
});

test("_csrf を削除して送るとログアウトを拒否する", async ({ page }) => {
  await logIn(page);
  await csrfInput(page).evaluate((input) => {
    input.remove();
  });

  expect(await expectLogoutRejected(page)).toBeNull();
});

// csrf.spa() はフォームの _csrf を XorCsrfTokenRequestAttributeHandler で URL-safe Base64 として復号する。
// マスクしない __Host-XSRF-TOKEN の値（36 文字の UUID）は 27 バイトになり、必要な 72 バイトと一致しないため token は null になる。
test("_csrf にマスクしない Cookie の値を入れて送るとログアウトを拒否する", async ({
  page,
  context,
}) => {
  await logIn(page);
  const csrfCookie = await findCookie(context, "__Host-XSRF-TOKEN");
  // __Host- の条件（ADR-066）。sameSite は Playwright が属性のない Cookie も "Lax" で埋めるため、下の Set-Cookie の文字列で確かめる。
  expect(csrfCookie).toMatchObject({ secure: true, path: "/", httpOnly: false });
  const token = csrfCookie.value;
  await csrfInput(page).evaluate((input: HTMLInputElement, value) => {
    input.value = value;
  }, token);

  // token を送らなかったのではなく、マスクしない値を送って拒否されたことを確かめる。
  expect(await expectLogoutRejected(page)).toBe(token);
});

// task e2e は Faro を有効にしてビルドする（ADR-068）。fixtures の既定の 202 を page.route の 503 で上書きする。
test("/collect が 503 を返しても、ログインとログアウトのフォームを送信できる", async ({ page }) => {
  // 最初の goto の前に登録し、ログインの間に送られても 503 にする。
  await page.route("**/collect", (route) => route.fulfill({ status: 503 }));
  // 最初の view_changed の公開 wire payload で SDK の初期化完了を確かめる。
  const ready = page.waitForRequest(
    (request) =>
      new URL(request.url()).pathname === "/collect" &&
      (request.postData() ?? "").includes('"view_changed"'),
  );
  await logIn(page);
  await expect(homeHeading(page)).toBeVisible();
  await ready;

  const collect = page.waitForRequest(
    (request) =>
      new URL(request.url()).pathname === "/collect" &&
      (request.postData() ?? "").includes("e2e telemetry probe"),
  );
  // setTimeout の中で投げ、page.evaluate を失敗させずに window.onerror へ届ける。
  await page.evaluate(() => {
    setTimeout(() => {
      throw new Error("e2e telemetry probe");
    }, 0);
  });
  await collect;

  await logoutButton(page).click();
  await expectLoggedOutPage(page);
});

// CsrfFilter は Cookie のない要求でだけ新しい token を発行する（RepositoryDeferredCsrfToken）。
// page.request は context の __Host- の Cookie を http://localhost にも送るため、Cookie を共有しない request で送る。
// request が Cookie を持たないのは、file 先頭の test.use で storageState を空にしているためである。
// 別の spec へ移すときは storageState を空にする。
// ApiContractTest は MockMvc で Set-Cookie の文字列を作らず、DAST は 10054 で失敗しないので、実際の server の文字列はここで確かめる。
test("CSRF の Cookie を __Host- の属性で発行する", async ({ request }) => {
  const response = await request.post("/api/missing");
  expect(response.status()).toBe(403);
  const setCookie = response
    .headersArray()
    .filter(({ name }) => name.toLowerCase() === "set-cookie")
    .map(({ value }) => value)
    .find((value) => value.startsWith("__Host-XSRF-TOKEN="));
  expect(setCookie).toMatch(/; Path=\/(?:;|$)/u);
  expect(setCookie).toMatch(/; Secure(?:;|$)/u);
  expect(setCookie).toMatch(/; SameSite=Lax(?:;|$)/u);
  expect(setCookie).not.toMatch(/; (?:Domain|HttpOnly)/iu);
});

// AppShell の header で「ログアウト」が最初に focus を受け取る要素であることに依存する。
// header の前に focus を受け取る要素を足したときは、Tab の回数を見直す。
test("keyboard だけでログアウトできる", async ({ page }) => {
  await logIn(page);
  await expect(homeHeading(page)).toBeVisible();

  await page.keyboard.press("Tab");
  await expect(logoutButton(page)).toBeFocused();
  await page.keyboard.press("Enter");

  await expectLoggedOutPage(page);
});
