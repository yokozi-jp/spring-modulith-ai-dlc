import { defineConfig, devices } from "@playwright/test";

import { authFile, isCI } from "./e2e/environment";

// Keycloak の redirect URI と Cookie の host に合わせ、127.0.0.1 ではなく localhost を使う。
const baseURL = "http://localhost:5173";

export default defineConfig({
  testDir: "e2e",
  // 実行順への依存を早く見つけるため、ファイル内のテストも並列に実行する。workers は既定値のまま指定しない。
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL,
    locale: "ja-JP",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [
    { name: "setup", testMatch: /\.setup\.ts$/u, use: { ...devices["Desktop Chrome"] } },
    {
      name: "chromium",
      testMatch: /\.spec\.ts$/u,
      dependencies: ["setup"],
      use: { ...devices["Desktop Chrome"], storageState: authFile },
    },
  ],
  // build 済みの dist を配信する。既に 5173 で何かが応答していれば、別の server を検証しないよう失敗する。
  webServer: {
    command: "vp preview --mode test",
    url: baseURL,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
