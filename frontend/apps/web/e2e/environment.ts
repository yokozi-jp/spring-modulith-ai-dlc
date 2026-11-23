import { expect } from "@playwright/test";
import type { BrowserContext, Cookie, Page } from "@playwright/test";
import { loadEnv } from "vite-plus";

// 作業ディレクトリに依存しないよう、このファイルの位置からパスを決める。
function pathFromHere(relative: string): string {
  return decodeURIComponent(new URL(relative, import.meta.url).pathname);
}

// vp run e2e を単独で実行しても task e2e と同じ値になるよう、ルートの .env.test（と .env.test.local）から読む。
// 同じ名前の環境変数があれば、その値を優先する（Vite の loadEnv の仕様）。
const env = loadEnv("test", pathFromHere("../../../.."), [
  "E2E_",
  "CI",
  "OIDC_ISSUER_URI",
  "PAYMENT_GATEWAY_BASE_URL",
]);

function requireEnv(name: "E2E_USERNAME" | "E2E_PASSWORD" | "PAYMENT_GATEWAY_BASE_URL"): string {
  const value = env[name];
  if (value === undefined || value === "") {
    throw new Error(
      `${name} をルートの .env.test に設定してください。.env.test が無いときは .env.test.example をコピーして作ります。`,
    );
  }
  return value;
}

// vite.config.ts の preview.port と同じ変数で、並行する task e2e ごとに port をずらす。
function webPort(): number {
  const value = env.E2E_WEB_PORT ?? "5173";
  const port = Number(value);
  if (!/^\d+$/u.test(value) || port < 1 || port > 65_535) {
    throw new Error(`E2E_WEB_PORT は 1 から 65535 の整数にしてください：${value}`);
  }
  return port;
}

export const isCI = Boolean(env.CI);
const credentials = {
  username: requireEnv("E2E_USERNAME"),
  password: requireEnv("E2E_PASSWORD"),
};
// ホストへ公開した compose-test の決済代行の WireMock（.env.test の PAYMENT_GATEWAY_BASE_URL）。
export const paymentGatewayUrl = requireEnv("PAYMENT_GATEWAY_BASE_URL");
// Keycloak の redirect URI と Cookie の host に合わせ、127.0.0.1 ではなく localhost を使う。
export const webOrigin = `http://localhost:${webPort()}`;
// task fe-check は .env.test なしでこのファイルを import するため、compose-test の既定の issuer の origin を既定値にする。
export const idpOrigin = new URL(env.OIDC_ISSUER_URI ?? "http://127.0.0.1:8081").origin;
// backend の server.servlet.session.cookie.name と同じ値にする（ADR-117）。
export const sessionCookieName = "__Host-APP_SESSION";
// playwright-report/ と test-results/ の外に置き、CI の artifact に入れない。
export const authFile = pathFromHere(".auth/user.json");

// Keycloak の資格情報の入力画面で test-user としてログインする。
export async function signInOnKeycloak(page: Page): Promise<void> {
  await page.getByLabel("Username or email").fill(credentials.username);
  await page.getByLabel("Password", { exact: true }).fill(credentials.password);
  await page.getByRole("button", { name: "Sign In" }).click();
}

// test-user は seed（backend の TenancySeeds）で 2 つのテナントに属するため、ログインの後にテナントを選ぶ画面が出る。
export async function chooseSeededTenant(page: Page): Promise<void> {
  await expect(page).toHaveURL(`${webOrigin}/tenants`);
  await page.getByRole("button", { name: "サンプル商事" }).click();
  await expect(page).toHaveURL(`${webOrigin}/`);
}

// Keycloak の資格情報の入力画面でログインし、テナントを選んでトップページへ戻る。
export async function signInAndChooseTenant(page: Page): Promise<void> {
  await signInOnKeycloak(page);
  await chooseSeededTenant(page);
}

// 認証の開始から Keycloak でログインし、テナントを選んでトップページへ戻る。
export async function logIn(page: Page): Promise<void> {
  await page.goto("/oauth2/authorization/web");
  await signInAndChooseTenant(page);
}

// Cookie がないまま undefined を送り、別の理由で失敗するのを防ぐ。
export async function findCookie(context: BrowserContext, name: string): Promise<Cookie> {
  const cookies = await context.cookies();
  const cookie = cookies.find((candidate) => candidate.name === name);
  if (cookie === undefined || cookie.value === "") {
    throw new Error(`${name} Cookie がありません`);
  }
  return cookie;
}
