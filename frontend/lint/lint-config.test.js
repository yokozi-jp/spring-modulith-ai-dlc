import { describe, expect, it } from "vite-plus/test";

import { appDirectory, lintFixtureProject } from "./lint-project.js";

const apiDirectory = "packages/api";
const diagnostics = lintFixtureProject(".");

/**
 * @param {string} file package の src からの相対パス
 * @param {string} [directory] file を置いた package。省略するとアプリ
 */
function codesIn(file, directory = appDirectory) {
  return diagnostics
    .filter((diagnostic) => diagnostic.filename === `${directory}/src/${file}`)
    .map((diagnostic) => diagnostic.code);
}

/**
 * @param {string} file
 * @param {string} code
 * @param {string} [directory]
 */
function countIn(file, code, directory = appDirectory) {
  return codesIn(file, directory).filter((found) => found === code).length;
}

describe("fixture に適用したプロジェクトの lint 設定", () => {
  describe("feature 間の境界", () => {
    it("別の feature の静的 import、再 export、動的 import を禁止する", () => {
      expect(
        countIn("features/order/cross-feature.ts", "feature-boundaries(no-cross-feature-import)"),
      ).toBe(3);
    });

    it("feature から route と route tree の import を禁止する", () => {
      expect(countIn("features/order/route-import.ts", "eslint(no-restricted-imports)")).toBe(2);
    });

    it("feature からテレメトリ SDK の import を禁止する", () => {
      expect(countIn("features/order/telemetry-sdk.ts", "eslint(no-restricted-imports)")).toBe(2);
    });

    it("自身の feature と API client の import を許可する", () => {
      expect(codesIn("features/order/own-feature.ts")).toStrictEqual([]);
    });
  });

  describe("route のファイル", () => {
    it("route から Base UI とテスト用部品の import を禁止する", () => {
      expect(countIn("routes/restricted.tsx", "eslint(no-restricted-imports)")).toBe(2);
    });

    it("route から feature の import を許可する", () => {
      expect(codesIn("routes/orders.tsx")).toStrictEqual([]);
    });
  });

  describe("共有層", () => {
    it("共有コードから feature と route の import を禁止する", () => {
      expect(countIn("lib/feature-import.ts", "eslint(no-restricted-imports)")).toBe(1);
      expect(countIn("components/route-import.tsx", "eslint(no-restricted-imports)")).toBe(1);
    });

    it("lib から components の import を禁止する", () => {
      expect(countIn("lib/component-import.ts", "eslint(no-restricted-imports)")).toBe(1);
    });

    it("components/ui から ui 以外の components の import を禁止し、ui の primitive は許可する", () => {
      expect(
        countIn("components/ui/app-component-import.ts", "eslint(no-restricted-imports)"),
      ).toBe(1);
    });

    it("共有コードから API client の import を許可する", () => {
      expect(codesIn("lib/clean.ts")).toStrictEqual([]);
    });

    it("テレメトリ SDK の import を lib/telemetry.ts だけに許可する", () => {
      expect(codesIn("lib/telemetry.ts")).toStrictEqual([]);
    });
  });

  describe("UI", () => {
    describe("Base UI の import", () => {
      it("components/ui の外では Base UI を禁止する", () => {
        expect(countIn("features/order/base-ui.tsx", "eslint(no-restricted-imports)")).toBe(1);
      });

      it("components/ui では Base UI を許可する", () => {
        expect(codesIn("components/ui/primitive.tsx")).toStrictEqual([]);
      });

      it("components/ui でも共有層とテスト用部品の import を禁止する", () => {
        expect(countIn("components/ui/shared-layer.tsx", "eslint(no-restricted-imports)")).toBe(2);
      });

      it("テストファイルでも Base UI を禁止する", () => {
        expect(countIn("features/order/base-ui.test.ts", "eslint(no-restricted-imports)")).toBe(1);
      });
    });

    describe("Tailwind の class", () => {
      it("ルートから lint しても、アプリの theme を解決する", () => {
        const messages = diagnostics
          .filter((diagnostic) => diagnostic.filename.endsWith("features/order/theme-class.tsx"))
          .filter(
            (diagnostic) => diagnostic.code === "better-tailwindcss(enforce-canonical-classes)",
          )
          .map((diagnostic) => diagnostic.message);

        expect(messages).toHaveLength(1);
        expect(messages[0]).toContain('simplified to "bg-primary"');
      });

      it("値から組み立てた class 名と、関数に渡した key で選んだ class 名を禁止する", () => {
        expect(
          countIn("features/order/dynamic-class.tsx", "tailwind-classes(no-dynamic-class-name)"),
        ).toBe(5);
      });

      it("完全な class 名を値と連結したものと、組み立てた key で選んだ class 名を許可する", () => {
        expect(codesIn("features/order/static-class.tsx")).toStrictEqual([]);
      });
    });

    describe("link と button の組み合わせ", () => {
      it("link の中の button と、Button の render に渡した link を禁止する", () => {
        expect(
          countIn("features/order/button-in-link.tsx", "ui-composition(no-button-in-link)"),
        ).toBe(6);
      });

      it("button の隣の link を許可する", () => {
        expect(codesIn("features/order/link-beside-button.tsx")).toStrictEqual([]);
      });
    });
  });

  describe("Effect", () => {
    it("名前付き import、別名の import、member 参照、分割代入、再 export の Effect Hook を禁止する", () => {
      expect(countIn("features/order/effect.tsx", "react-rules(no-effect)")).toBe(6);
    });

    it("他の Hook と、useEffect プロパティを持つローカルのオブジェクトを許可する", () => {
      expect(codesIn("features/order/effect-clean.tsx")).toStrictEqual([]);
    });
  });

  describe("ネットワークアクセス", () => {
    it("@frontend/api の外では fetch と XMLHttpRequest を禁止する", () => {
      expect(countIn("lib/network.ts", "eslint(no-restricted-globals)")).toBe(2);
      expect(countIn("lib/network.ts", "eslint(no-restricted-properties)")).toBe(2);
    });

    it("@frontend/api の中では fetch と XMLHttpRequest を許可する", () => {
      expect(codesIn("client.ts", apiDirectory)).toStrictEqual([]);
    });
  });

  describe("HTML sink の制限", () => {
    it("どの override でも DOMParser を禁止する", () => {
      expect(countIn("html-sink.ts", "eslint(no-restricted-globals)", apiDirectory)).toBe(1);
      expect(countIn("lib/html-sink.ts", "eslint(no-restricted-globals)")).toBe(1);
    });
  });

  describe("テストコード", () => {
    it("日本語を含まない it、test、describe の title を禁止し、1 語のコードの名前の describe は許可する", () => {
      expect(countIn("features/order/test-title.test.ts", "vitest(valid-title)")).toBe(5);
    });

    it("production source からテスト用部品の import を禁止する", () => {
      expect(countIn("components/msw-import.ts", "eslint(no-restricted-imports)")).toBe(4);
    });

    it("src/testing とテストファイルではテスト用部品を許可する", () => {
      expect(codesIn("testing/server.ts")).toStrictEqual([]);
      expect(codesIn("testing/render.ts")).toStrictEqual([]);
      expect(codesIn("features/order/order.test.ts")).toStrictEqual([]);
    });

    it("vi.mock と vi.doMock による module の mock を禁止する", () => {
      expect(
        countIn("features/order/module-mock.test.ts", "vitest(no-restricted-vi-methods)"),
      ).toBe(2);
    });

    it("fixed timeout を指定した vi.waitFor と vi.waitUntil を禁止する", () => {
      expect(
        countIn("features/order/fixed-timeout-wait.test.ts", "vitest(no-restricted-vi-methods)"),
      ).toBe(2);
    });

    it("expect.poll と Testing Library の waitFor を許可する", () => {
      expect(codesIn("features/order/configured-timeout-wait.test.ts")).toStrictEqual([]);
    });

    it("vi.stubGlobal と vi.spyOn による global の差し替えを許可する", () => {
      expect(codesIn("features/order/global-stub.test.ts")).toStrictEqual([]);
    });

    it("production code では 10 個を超える import を禁止する", () => {
      expect(countIn("features/order/many-imports.ts", "import(max-dependencies)")).toBe(1);
    });

    it("テストファイルでは 10 個を超える import を許可する", () => {
      expect(codesIn("features/order/many-imports.test.ts")).toStrictEqual([]);
    });
  });

  // docs/tooling/lint-and-test.md の「Lintを通る書き方」の規則。
  describe("書き方", () => {
    describe("void", () => {
      it("文としての void を許可する", () => {
        expect(codesIn("features/order/refresh-button.tsx")).toStrictEqual([]);
      });

      it("式の中の void を禁止する", () => {
        expect(countIn("lib/void-expression.ts", "eslint(no-void)")).toBe(1);
      });
    });

    describe("undefined", () => {
      it("undefined との比較を許可する", () => {
        expect(codesIn("lib/is-missing.ts")).toStrictEqual([]);
      });
    });

    describe("関数の定義", () => {
      it("変数に代入した arrow function と関数式を禁止する", () => {
        expect(countIn("lib/arrow-function.ts", "eslint(func-style)")).toBe(2);
      });

      it("型注釈を付けた関数式を禁止する", () => {
        expect(countIn("lib/typed-function-expression.ts", "eslint(func-style)")).toBe(1);
      });

      it("型注釈を付けた arrow function と、callback の arrow function を許可する", () => {
        expect(codesIn("lib/typed-arrow-function.ts")).toStrictEqual([]);
      });
    });
  });

  describe("ファイル名", () => {
    it("PascalCase のファイル名を禁止する", () => {
      expect(countIn("features/order/OrderSummary.tsx", "unicorn(filename-case)")).toBe(1);
    });

    it("kebab-case のファイル名を許可する", () => {
      expect(codesIn("features/order/order-summary.tsx")).toStrictEqual([]);
    });
  });
});
