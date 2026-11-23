import { expect, test } from "./fixtures";

// 保存済みの storageState を持たない request で、実際の server の Set-Cookie を確かめる。
test.use({ storageState: { cookies: [], origins: [] } });

// 認可リクエストの保存でセッションが作られ、その応答がセッションの Cookie を発行する（auth.setup.ts と同じ経路）。
// MockMvc は埋め込みサーバのない構成で Cookie の名前と属性を適用しないため、実際の server の文字列はここで確かめる。
test("セッションの Cookie を __Host- の属性で発行する", async ({ request }) => {
  const response = await request.get("/oauth2/authorization/web", { maxRedirects: 0 });
  const setCookie = response
    .headersArray()
    .filter(({ name }) => name.toLowerCase() === "set-cookie")
    .map(({ value }) => value)
    .find((value) => value.startsWith("__Host-APP_SESSION="));
  expect(setCookie).toMatch(/; Path=\/(?:;|$)/u);
  expect(setCookie).toMatch(/; Secure(?:;|$)/u);
  expect(setCookie).toMatch(/; HttpOnly(?:;|$)/u);
  expect(setCookie).toMatch(/; SameSite=Lax(?:;|$)/u);
  expect(setCookie).not.toMatch(/; Domain/iu);
});
