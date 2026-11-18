import { describe, expect, it } from "vite-plus/test";

import { appDirectory, lintFixtureProject } from "./lint-project.js";

const themeClass = "apps/web/src/features/order/theme-class.tsx";

// CSS を解決できないと、規則は「entry point が見つからない」警告を同じ code で出す。
// 警告ではなく、アプリの theme から決まる提案が出ることで、CSS を解決したと確かめる。
describe("アプリの Tailwind CSS の entry point", () => {
  it.each([
    ["workspace のルート", "."],
    ["アプリの directory", appDirectory],
  ])(
    "%s から lint しても、アプリの theme を解決する",
    (_name, cwd) => {
      const messages = lintFixtureProject(cwd, themeClass)
        .filter((diagnostic) => diagnostic.code === "better-tailwindcss(enforce-canonical-classes)")
        .map((diagnostic) => diagnostic.message);

      expect(messages).toHaveLength(1);
      expect(messages[0]).toContain('simplified to "bg-primary"');
      // vp lint を別プロセスで起動する時間が、既定の 5 秒を超える。lint-project.js の打ち切りと同じ 60 秒にする。
    },
    60_000,
  );
});
