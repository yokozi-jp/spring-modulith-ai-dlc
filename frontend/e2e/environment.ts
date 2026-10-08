import type { Page } from "@playwright/test";
import { loadEnv } from "vite-plus";

// 作業ディレクトリに依存しないよう、このファイルの位置からパスを決める。
const pathFromHere = (relative: string): string =>
  decodeURIComponent(new URL(relative, import.meta.url).pathname);

// pnpm e2e を単独で実行しても task e2e と同じ値になるよう、ルートの .env.test（と .env.test.local）から読む。
// 同じ名前の環境変数があれば、その値を優先する（Vite の loadEnv の仕様）。
const env = loadEnv("test", pathFromHere("../.."), ["E2E_", "CI", "PAYMENT_GATEWAY_BASE_URL"]);

function requireEnv(name: "E2E_USERNAME" | "E2E_PASSWORD" | "PAYMENT_GATEWAY_BASE_URL"): string {
  const value = env[name];
  if (value === undefined || value === "") {
    throw new Error(
      `${name} をルートの .env.test に設定してください。.env.test が無いときは .env.test.example をコピーして作ります。`,
    );
  }
  return value;
}

export const isCI = Boolean(env.CI);
const credentials = {
  username: requireEnv("E2E_USERNAME"),
  password: requireEnv("E2E_PASSWORD"),
};
// ホストへ公開した compose-test の決済代行の WireMock（.env.test の PAYMENT_GATEWAY_BASE_URL）。
export const paymentGatewayUrl = requireEnv("PAYMENT_GATEWAY_BASE_URL");
// playwright-report/ と test-results/ の外に置き、CI の artifact に入れない。
export const authFile = pathFromHere(".auth/user.json");

// Keycloak の資格情報の入力画面で test-user としてログインする。
export async function signInOnKeycloak(page: Page): Promise<void> {
  await page.getByLabel("Username or email").fill(credentials.username);
  await page.getByLabel("Password", { exact: true }).fill(credentials.password);
  await page.getByRole("button", { name: "Sign In" }).click();
}
