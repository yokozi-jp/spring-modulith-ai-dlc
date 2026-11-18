import { describe, expect, it } from "vite-plus/test";

import { appDirectory, lintFixtureProject, uiDirectory } from "./lint-project.js";

// CSS を解決できないと、規則は「entry point が見つからない」警告を同じ code で出す。
// 警告ではなく、package の theme から決まる提案が出ることで、CSS を解決したと確かめる。
// ルートから実行した場合は lint-config.test.js が確かめる。
describe("package の Tailwind CSS の entry point", () => {
  it.each([
    [appDirectory, `${appDirectory}/src/features/order/theme-class.tsx`],
    [uiDirectory, `${uiDirectory}/src/theme-class.tsx`],
  ])(
    "%s の directory から lint しても、その package の theme を解決する",
    (directory, target) => {
      const messages = lintFixtureProject(directory, target)
        .filter((diagnostic) => diagnostic.code === "better-tailwindcss(enforce-canonical-classes)")
        .map((diagnostic) => diagnostic.message);

      expect(messages).toHaveLength(1);
      expect(messages[0]).toContain('simplified to "bg-primary"');
    },
    // vp lint を別プロセスで起動する時間が、既定の 5 秒を超える。lint-project.js の打ち切りと同じ 60 秒にする。
    60_000,
  );
});
