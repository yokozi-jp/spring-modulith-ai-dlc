import { defineConfig } from "vite-plus";

// lint と fmt の設定はルートの vite.config.ts だけに書く（ADR-092）。
export default defineConfig({
  test: {
    // MSW の server と後片付けを全テストで共有する。
    setupFiles: ["./src/testing/setup.ts"],
    coverage: {
      provider: "v8",
      include: ["src/**/*.ts"],
      // 生成物は Orval が作る。src/testing はテスト用の部品である。
      exclude: ["src/**/*.test.ts", "src/testing/**", "src/generated/**"],
      reporter: ["text", "html", "clover", "json"],
      // 全体の下限。切り出した時点の実測（branch 93.18%、line 100%）の少し下に置く（ADR-092）。
      // perFile はファイルごとの下限で、全 package で共通の値にする（ADR-076、lint/workspace-layout.test.js）。
      thresholds: { branches: 90, lines: 98, perFile: { branches: 60, lines: 65 } },
    },
  },
});
