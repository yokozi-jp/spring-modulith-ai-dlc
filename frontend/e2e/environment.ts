import { loadEnv } from "vite-plus";

// 作業ディレクトリに依存しないよう、このファイルの位置からパスを決める。
const pathFromHere = (relative: string): string =>
  decodeURIComponent(new URL(relative, import.meta.url).pathname);

// pnpm e2e を単独で実行しても task e2e と同じ値になるよう、ルートの .env.test（と .env.test.local）から読む。
// 同じ名前の環境変数があれば、その値を優先する（Vite の loadEnv の仕様）。
const env = loadEnv("test", pathFromHere("../.."), ["E2E_", "CI"]);

function requireEnv(name: "E2E_USERNAME" | "E2E_PASSWORD"): string {
  const value = env[name];
  if (value === undefined || value === "") {
    throw new Error(`${name} をルートの .env.test に設定してください。`);
  }
  return value;
}

export const isCI = Boolean(env.CI);
export const credentials = {
  username: requireEnv("E2E_USERNAME"),
  password: requireEnv("E2E_PASSWORD"),
};
// playwright-report/ と test-results/ の外に置き、CI の artifact に入れない。
export const authFile = pathFromHere(".auth/user.json");
