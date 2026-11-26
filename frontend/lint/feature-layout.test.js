import { readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vite-plus/test";

const featuresRoot = fileURLToPath(new URL("../apps/web/src/features", import.meta.url));
const roleDirectories = new Set(["pages", "components", "hooks"]);

/** @typedef {{ directory: string | undefined, name: string, isTest: boolean, isPage: boolean }} Entry */

/** @type {Array<{ applies: (entry: Entry) => boolean, message: string }>} */
const rules = [
  {
    applies: ({ directory, name }) => directory === undefined && name.endsWith(".tsx"),
    message: "feature の直下に .tsx を置かない。画面は pages/、component は components/ へ置く",
  },
  {
    applies: ({ directory }) => directory !== undefined && !roleDirectories.has(directory),
    message: "feature の直下のディレクトリは pages、components、hooks だけにする",
  },
  {
    applies: ({ directory, isPage }) => isPage && directory !== "pages",
    message: "*-page.tsx は pages/ に置く",
  },
  {
    applies: ({ directory, isPage, isTest }) => directory === "pages" && !isPage && !isTest,
    message: "pages/ に置くのは *-page.tsx とテストだけにする",
  },
  {
    applies: ({ directory, isTest, name }) =>
      directory === "hooks" && !isTest && !/^use-.+\.ts$/u.test(name),
    message: "hooks/ に置くのは use-*.ts とテストだけにする",
  },
];

/**
 * feature の配置の違反を返す。ファイル名で決まる規則は lint では書けないため、パスの一覧に対して検査する。
 * @param {string[]} files features/ からの相対パス（orders/pages/orders-page.tsx など）
 * @returns {string[]}
 */
function layoutViolations(files) {
  return files.flatMap((file) => {
    const [, ...inFeature] = file.split("/");
    const name = String(inFeature.at(-1));
    /** @type {Entry} */
    const entry = {
      directory: inFeature.length > 1 ? inFeature[0] : undefined,
      name,
      isTest: /\.test\.tsx?$/u.test(name),
      isPage: name.endsWith("-page.tsx"),
    };
    const broken = rules.find(({ applies }) => applies(entry));
    return broken ? [`${file}: ${broken.message}`] : [];
  });
}

describe("feature のファイルの配置", () => {
  it("実際の features/ は配置の規則に従う", () => {
    const files = readdirSync(featuresRoot, { recursive: true, withFileTypes: true })
      .filter((entry) => entry.isFile())
      .map((entry) => `${entry.parentPath}/${entry.name}`.slice(featuresRoot.length + 1));

    expect(files.length).toBeGreaterThan(0);
    expect(layoutViolations(files)).toStrictEqual([]);
  });

  describe("規則の範囲", () => {
    it.each([
      "orders/form.ts",
      "orders/form.test.ts",
      "orders/pages/orders-page.tsx",
      "orders/pages/order-detail-failures.test.tsx",
      "orders/components/orders-table.tsx",
      "orders/hooks/use-order-detail.ts",
      "orders/hooks/use-order-detail.test.ts",
    ])("%s を許可する", (file) => {
      expect(layoutViolations([file])).toStrictEqual([]);
    });

    it.each([
      "orders/orders-page.tsx",
      "orders/orders-table.tsx",
      "orders/utils/format.ts",
      "orders/components/orders-page.tsx",
      "orders/hooks/orders-page.tsx",
      "orders/pages/orders-table.tsx",
      "orders/hooks/format.ts",
    ])("%s を禁止する", (file) => {
      expect(layoutViolations([file])).toHaveLength(1);
    });
  });
});
