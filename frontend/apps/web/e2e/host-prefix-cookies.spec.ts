import type { APIResponse } from "@playwright/test";

import { sessionCookieName } from "./environment";
import { expect, test } from "./fixtures";

// page.request は context の __Host- の Cookie を http://localhost にも送るため、Cookie を持たない request で送る。
// CsrfFilter は Cookie のない要求でだけ新しい token を発行し（RepositoryDeferredCsrfToken）、
// 認可リクエストの保存はセッションのない要求でだけセッションを作るためである。
test.use({ storageState: { cookies: [], origins: [] } });

// __Host- の条件（Secure、Path=/、Domain なし）と SameSite=Lax を、実際の server の Set-Cookie の文字列で確かめる。
// MockMvc は Set-Cookie の文字列を作らず、Cookie の名前も server.servlet.session.cookie.* を適用しない。
function expectHostPrefixCookie(response: APIResponse, name: string, httpOnly: boolean): void {
  const setCookie = response
    .headersArray()
    .filter((header) => header.name.toLowerCase() === "set-cookie")
    .map(({ value }) => value)
    .find((value) => value.startsWith(`${name}=`));
  if (setCookie === undefined) {
    throw new Error(`${name} の Set-Cookie がありません`);
  }
  expect(setCookie).toMatch(/; Path=\/(?:;|$)/u);
  expect(setCookie).toMatch(/; Secure(?:;|$)/u);
  expect(setCookie).toMatch(/; SameSite=Lax(?:;|$)/u);
  expect(setCookie).not.toMatch(/; Domain/iu);
  if (httpOnly) {
    expect(setCookie).toMatch(/; HttpOnly(?:;|$)/u);
  } else {
    expect(setCookie).not.toMatch(/; HttpOnly/iu);
  }
}

test("CSRF の Cookie を __Host- の属性で、SPA が読めるよう HttpOnly なしで発行する", async ({
  request,
}) => {
  const response = await request.post("/api/missing");
  expect(response.status()).toBe(403);
  expectHostPrefixCookie(response, "__Host-XSRF-TOKEN", false);
});

test("セッションの Cookie を __Host- の属性と HttpOnly で発行する", async ({ request }) => {
  const response = await request.get("/oauth2/authorization/web", { maxRedirects: 0 });
  expectHostPrefixCookie(response, sessionCookieName, true);
});
