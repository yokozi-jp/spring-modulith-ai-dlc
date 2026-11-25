import { defineConfig, devices } from "@playwright/test";

import { authFile, isCI, webOrigin } from "./e2e/environment";

export default defineConfig({
  testDir: "e2e",
  // 実行順への依存を早く見つけるため、ファイル内のテストも並列に実行する。workers は既定値のまま指定しない。
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: webOrigin,
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
  // build 済みの dist を配信する。既に E2E_WEB_PORT（既定は 5173）で何かが応答していれば、別の server を検証しないよう失敗する。
  webServer: {
    command: "vp preview --mode test",
    url: webOrigin,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
