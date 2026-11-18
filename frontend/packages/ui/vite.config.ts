import { defineConfig } from "vite-plus";

export default defineConfig({
  // エイリアスの正本は tsconfig.json の paths とする。
  resolve: { tsconfigPaths: true },
  test: {
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/**/*.{test,spec}.{ts,tsx}"],
      // json-summary は task fe-coverage がファイルごとの下限の検査に読む。
      reporter: ["text", "html", "clover", "json", "json-summary"],
      // 全体の下限。切り出した時点の実測（branch 100%、line 100%）の少し下に置く（ADR-092）。
      thresholds: { branches: 95, lines: 95 },
    },
  },
});
