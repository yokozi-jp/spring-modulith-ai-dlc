import { defineConfig } from "vite-plus";

export default defineConfig({
  // エイリアスの正本は tsconfig.json の paths とする。
  resolve: { tsconfigPaths: true },
  test: {
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/**/*.{test,spec}.{ts,tsx}"],
      reporter: ["text", "html", "clover", "json"],
      // 全体の下限。切り出した時点の実測（branch 100%、line 100%）の少し下に置く（ADR-092）。
      // perFile はファイルごとの下限で、全 package で共通の値にする（ADR-076、lint/workspace-layout.test.js）。
      thresholds: { branches: 95, lines: 95, perFile: { branches: 60, lines: 65 } },
    },
  },
});
