// oxlint-disable max-lines -- 実際の設定を fixture に適用した結果を 1 つのファイルに集めるため、行数で分割しない。
import { describe, expect, it } from "vite-plus/test";

import {
  appDirectory,
  lintFixtureProject,
  secondAppDirectory,
  uiDirectory,
} from "./lint-project.js";

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

/** @param {string} file */
function canonicalMessagesOf(file) {
  return diagnostics
    .filter((diagnostic) => diagnostic.filename.endsWith(file))
    .filter((diagnostic) => diagnostic.code === "better-tailwindcss(enforce-canonical-classes)")
    .map((diagnostic) => diagnostic.message);
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

    it("hooks/ から画面、component、共有の component の import を禁止する", () => {
      expect(countIn("features/order/hooks/page-import.ts", "eslint(no-restricted-imports)")).toBe(
        3,
      );
    });

    // 禁止する書き方は、React 本体と部分 path、react-dom、react-i18next、TanStack の react-*、@frontend/ui、lucide-react、画面と component と Hook の alias と ./ の 15 個である。
    it("feature の直下の module から React と React の package、同じ feature の画面と component と Hook の import を禁止する", () => {
      expect(countIn("features/order/module-react.ts", "eslint(no-restricted-imports)")).toBe(15);
    });

    it("module のテストでは、React の import と補助の Hook の定義を許可する", () => {
      expect(codesIn("features/order/module-react.test.ts")).toStrictEqual([]);
    });

    it("form.ts と queries.ts は TanStack の react-* を使えるが、React 本体は禁止する", () => {
      expect(codesIn("features/order/form.ts")).toStrictEqual([]);
      expect(codesIn("features/order/queries.ts")).toStrictEqual([]);
      expect(countIn("features/customer/form.ts", "eslint(no-restricted-imports)")).toBe(1);
      expect(codesIn("features/customer/queries.ts")).toStrictEqual([]);
    });

    // 実物のアプリが 1 つの間は、apps/web の設定を複製した fixture のアプリで、規則が apps/* に効くことを確かめる。
    it("別のアプリでも、別の feature の import と、feature から route の import を禁止する", () => {
      expect(
        countIn(
          "features/order/cross-feature.ts",
          "feature-boundaries(no-cross-feature-import)",
          secondAppDirectory,
        ),
      ).toBe(3);
      expect(
        countIn(
          "features/order/route-import.ts",
          "eslint(no-restricted-imports)",
          secondAppDirectory,
        ),
      ).toBe(2);
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
    it("共有コードから feature、route、route tree の import を禁止する", () => {
      expect(countIn("lib/feature-import.ts", "eslint(no-restricted-imports)")).toBe(1);
      expect(countIn("components/route-import.tsx", "eslint(no-restricted-imports)")).toBe(2);
    });

    it("components から api-fetch と生成 API の endpoints、models、zod の import を禁止し、api-error と csrf を許可する", () => {
      expect(countIn("components/generated-api-import.ts", "eslint(no-restricted-imports)")).toBe(
        4,
      );
      expect(codesIn("components/api-entry-import.ts")).toStrictEqual([]);
    });

    it("lib から components の import を禁止する", () => {
      expect(countIn("lib/component-import.ts", "eslint(no-restricted-imports)")).toBe(1);
    });

    it("共有コードから API client の import を許可する", () => {
      expect(codesIn("lib/clean.ts")).toStrictEqual([]);
    });

    it("テレメトリ SDK の import を lib/telemetry.ts だけに許可する", () => {
      expect(codesIn("lib/telemetry.ts")).toStrictEqual([]);
    });

    // 相対パスで package の外へ出る import は pnpm の解決を通らないので、依存の宣言では止まらない。import/no-relative-parent-imports が止める。
    it("ui からアプリ、api から ui、アプリから別のアプリへの相対パスの import を禁止する", () => {
      expect(
        countIn("app-relative-import.ts", "import(no-relative-parent-imports)", uiDirectory),
      ).toBe(1);
      expect(
        countIn("ui-relative-import.ts", "import(no-relative-parent-imports)", apiDirectory),
      ).toBe(1);
      expect(
        countIn("web-relative-import.ts", "import(no-relative-parent-imports)", secondAppDirectory),
      ).toBe(1);
    });
  });

  describe("UI", () => {
    describe("Base UI の import", () => {
      it("@frontend/ui の外では Base UI を禁止する", () => {
        expect(countIn("features/order/base-ui.tsx", "eslint(no-restricted-imports)")).toBe(1);
      });

      it("@frontend/ui の中では Base UI を許可する", () => {
        expect(codesIn("primitive.tsx", uiDirectory)).toStrictEqual([]);
      });

      it("アプリの components/ui には、Base UI を許可する層の override が残っていない", () => {
        expect(countIn("components/ui/base-ui.tsx", "eslint(no-restricted-imports)")).toBe(1);
      });

      it("@frontend/ui のテストファイルでも Base UI を禁止する", () => {
        expect(
          codesIn("base-ui.test.ts", uiDirectory).filter(
            (code) => code === "eslint(no-restricted-imports)",
          ),
        ).toHaveLength(1);
      });

      it("@frontend/ui でもテスト用部品の import を禁止する", () => {
        expect(
          codesIn("msw-import.ts", uiDirectory).filter(
            (code) => code === "eslint(no-restricted-imports)",
          ),
        ).toHaveLength(2);
      });

      it("テストファイルでも Base UI を禁止する", () => {
        expect(countIn("features/order/base-ui.test.ts", "eslint(no-restricted-imports)")).toBe(1);
      });
    });

    describe("shadcn の部品", () => {
      it("@frontend/ui の部品に渡した className の、部品が持つ見た目の class を禁止し、配置の class を許可する", () => {
        expect(countIn("features/order/restyle.tsx", "shadcn(no-restyle)")).toBe(1);
      });
    });

    describe("Tailwind の class", () => {
      it("ルートから lint しても、アプリと ui のそれぞれの theme を解決する", () => {
        for (const messages of [
          canonicalMessagesOf("apps/web/src/features/order/theme-class.tsx"),
          canonicalMessagesOf("packages/ui/src/theme-class.tsx"),
        ]) {
          expect(messages).toHaveLength(1);
          expect(messages[0]).toContain('simplified to "bg-primary"');
        }
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

    describe("アクセシビリティ", () => {
      it("中身が空の Link と Button を禁止する", () => {
        const file = "features/order/a11y-empty-link-button.tsx";
        expect(countIn(file, "jsx-a11y(anchor-has-content)")).toBe(1);
        expect(countIn(file, "jsx-a11y(control-has-associated-label)")).toBe(1);
      });

      it("中身のある Link の to を href として許可する", () => {
        expect(codesIn("features/order/a11y-link-with-content.tsx")).toStrictEqual([]);
      });

      it('<a href="#"> を禁止する', () => {
        expect(countIn("features/order/a11y-anchor-hash.tsx", "jsx-a11y(anchor-is-valid)")).toBe(1);
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

  describe("React の規則", () => {
    it("hooks/ の外の custom Hook の定義を禁止する", () => {
      expect(
        countIn("features/order/hook-in-component.tsx", "react-rules(no-hook-definition)"),
      ).toBe(1);
    });

    it("hooks/ の中の custom Hook の定義を許可する", () => {
      expect(codesIn("features/order/hooks/use-order-draft.ts")).toStrictEqual([]);
    });

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
    it("HTML を書き込む property と document.write、document.writeln を禁止する", () => {
      expect(countIn("lib/html-sink-properties.ts", "eslint(no-restricted-properties)")).toBe(10);
    });

    it("どの override でも DOMParser を禁止する", () => {
      expect(countIn("html-sink.ts", "eslint(no-restricted-globals)", apiDirectory)).toBe(1);
      expect(countIn("lib/html-sink.ts", "eslint(no-restricted-globals)")).toBe(1);
    });
  });

  describe("テストコード", () => {
    it("日本語を含まない it、test、describe の title を禁止し、1 語のコードの名前の describe は許可する", () => {
      expect(countIn("features/order/test-title.test.ts", "vitest(valid-title)")).toBe(5);
    });

    // vite.config.ts の vitest/* を off にする行が増えても、テストを弱める 5 つの規則は外れないことを確かめる。
    // .ts と .tsx、アプリと package の両方に fixture を置き、拡張子や package で絞った override による除外も捉える。
    it.each([
      "no-focused-tests",
      "no-disabled-tests",
      "expect-expect",
      "no-conditional-expect",
      "valid-expect",
    ])("テストを弱める記述を vitest(%s) が 1 件ずつ報告する", (rule) => {
      const code = `vitest(${rule})`;
      expect(countIn("features/order/weak-tests.test.ts", code)).toBe(1);
      expect(countIn("weak-tests.test.tsx", code, uiDirectory)).toBe(1);
    });

    it("production source から msw と msw/node を含むテスト用部品の import を禁止する", () => {
      expect(countIn("components/msw-import.ts", "eslint(no-restricted-imports)")).toBe(5);
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

  // docs/frontend/lint-config.md の「Lintを通る書き方」の規則。
  describe("書き方", () => {
    describe("void", () => {
      it("文としての void を許可する", () => {
        expect(codesIn("features/order/refresh-button.tsx")).toStrictEqual([]);
      });

      it("式の中の void を禁止する", () => {
        expect(countIn("lib/void-expression.ts", "eslint(no-void)")).toBe(1);
      });
    });

    describe("lint を止めるコメント", () => {
      it("同じ行に -- <理由> のない disable のコメントを禁止し、直し方を示す", () => {
        expect(
          countIn("lib/disable-without-reason.ts", "disable-reason(require-disable-reason)"),
        ).toBe(3);
        expect(
          diagnostics.find(
            (diagnostic) => diagnostic.code === "disable-reason(require-disable-reason)",
          )?.message,
        ).toContain("-- <reason>");
      });

      it("理由のある disable のコメントを許可する", () => {
        expect(codesIn("lib/disable-with-reason.ts")).toStrictEqual([]);
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
