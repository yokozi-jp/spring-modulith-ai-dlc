import type { Page } from "@playwright/test";

import { signInOnKeycloak } from "./environment";
import { expect, test } from "./fixtures";

// ログアウトで共有の storageState のセッションを終わらせないよう、専用の context でログインする。
test.use({ storageState: { cookies: [], origins: [] } });

const origin = "http://localhost:5173";
// compose-test が Keycloak の issuer を固定している（logout.spec.ts と同じ）。
const idpOrigin = "http://127.0.0.1:8081";
const endSessionEndpoint = `${idpOrigin}/realms/spring-modulith/protocol/openid-connect/logout?`;
// W3C Trace Context の version 00 で、sampled flag が 1 のもの（ADR-068、ブラウザで sampling しない）。
const sampledTraceparent = /^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/u;

interface RecordedRequest {
  url: URL;
  traceparent: string | undefined;
}

let requests: RecordedRequest[] = [];

test.beforeEach(({ page }) => {
  requests = [];
  page.on("request", (request) => {
    requests.push({ url: new URL(request.url()), traceparent: request.headers().traceparent });
  });
});

const traceparentsOf = (path: string): (string | undefined)[] =>
  requests
    .filter(({ url }) => url.origin === origin && url.pathname === path)
    .map(({ traceparent }) => traceparent);

async function logIn(page: Page): Promise<void> {
  await page.goto("/oauth2/authorization/web");
  await signInOnKeycloak(page);
  await expect(page).toHaveURL(`${origin}/`);
}

// 画面に /api/** を呼ぶ処理がまだないので、ページの中から直接呼ぶ。
async function fetchInPage(page: Page, path: string): Promise<void> {
  await page.evaluate(async (target) => {
    // oxlint-disable-next-line no-restricted-globals -- 計装が付ける header を、apiFetch を通さない素の fetch で観測する。
    await fetch(target);
  }, path);
}

// SDK は描画を待たずに読み込まれるので、traceparent が付くまで呼び直す。付いたら計装の初期化が終わったとみなす。
async function waitForTracing(page: Page): Promise<void> {
  await expect(async () => {
    await fetchInPage(page, "/api/missing");
    expect(traceparentsOf("/api/missing").at(-1)).toBeDefined();
  }).toPass();
}

// 記録したすべての要求のうち、traceparent を持つものが同一オリジンの /api/** だけであること。
function expectOnlyApiTraced(): void {
  const traced = requests.filter(({ traceparent }) => traceparent !== undefined);
  expect(traced.length).toBeGreaterThan(0);
  expect(
    traced
      .filter(({ url }) => url.origin !== origin || !/^\/api(?:\/|$)/u.test(url.pathname))
      .map(({ url }) => url.href),
  ).toEqual([]);
}

test("/api/** に sampled flag が 1 の traceparent を付け、ほかのパスと /collect には付けない", async ({
  page,
}) => {
  await logIn(page);
  await waitForTracing(page);
  expect(traceparentsOf("/api/missing").at(-1)).toMatch(sampledTraceparent);

  await fetchInPage(page, "/actuator/health/liveness");
  await fetchInPage(page, "/apix");
  expect([traceparentsOf("/actuator/health/liveness"), traceparentsOf("/apix")]).toEqual([
    [undefined],
    [undefined],
  ]);

  // Faro は span を /collect へ送る。その送信に traceparent を付けない。
  await expect.poll(() => traceparentsOf("/collect").length).toBeGreaterThan(0);
  expect(traceparentsOf("/collect")).not.toContainEqual(expect.any(String));
  expectOnlyApiTraced();
});

// 画面遷移は fetch と XHR の計装の対象外なので、この検査は回帰の検知用である。
// 別オリジンの fetch の除外は telemetry.test.ts で確かめる。
test("ログアウトで IdP へ行く要求に traceparent を付けない", async ({ page }) => {
  await logIn(page);
  await waitForTracing(page);

  const endSession = page.waitForRequest((request) => request.url().startsWith(endSessionEndpoint));
  await page.getByRole("button", { name: "ログアウト" }).click();
  await endSession;

  const idpTraceparents = requests
    .filter(({ url }) => url.origin === idpOrigin)
    .map(({ traceparent }) => traceparent);
  expect(idpTraceparents.length).toBeGreaterThan(0);
  expect(idpTraceparents).not.toContainEqual(expect.any(String));
  expectOnlyApiTraced();
});
