import { describe, expect, it } from "vite-plus/test";

import { appDirectory, lintFixtureProject } from "./lint-project.js";

const diagnostics = lintFixtureProject(".");

/** @param {string} file */
function codesIn(file) {
  return diagnostics
    .filter((diagnostic) => diagnostic.filename === `${appDirectory}/src/${file}`)
    .map((diagnostic) => diagnostic.code);
}

/**
 * @param {string} file
 * @param {string} code
 */
function countIn(file, code) {
  return codesIn(file).filter((found) => found === code).length;
}

describe("project lint config on fixtures", () => {
  describe("feature boundary", () => {
    it("rejects static, re-exported and dynamic imports of another feature", () => {
      expect(
        countIn("features/order/cross-feature.ts", "feature-boundaries(no-cross-feature-import)"),
      ).toBe(3);
    });

    it("rejects importing routes and the route tree from a feature", () => {
      expect(countIn("features/order/route-import.ts", "eslint(no-restricted-imports)")).toBe(2);
    });

    it("rejects the telemetry SDK from a feature", () => {
      expect(countIn("features/order/telemetry-sdk.ts", "eslint(no-restricted-imports)")).toBe(2);
    });

    it("allows the own feature and the API client", () => {
      expect(codesIn("features/order/own-feature.ts")).toStrictEqual([]);
    });
  });

  describe("routes", () => {
    it("rejects Base UI and test-only modules from a route", () => {
      expect(countIn("routes/restricted.tsx", "eslint(no-restricted-imports)")).toBe(2);
    });

    it("allows a route to compose a feature", () => {
      expect(codesIn("routes/orders.tsx")).toStrictEqual([]);
    });
  });

  describe("shared layer", () => {
    it("rejects features and routes from shared code", () => {
      expect(countIn("lib/feature-import.ts", "eslint(no-restricted-imports)")).toBe(1);
      expect(countIn("components/route-import.tsx", "eslint(no-restricted-imports)")).toBe(1);
    });

    it("rejects components from lib", () => {
      expect(countIn("lib/component-import.ts", "eslint(no-restricted-imports)")).toBe(1);
    });

    it("rejects components from api", () => {
      expect(countIn("api/component-import.ts", "eslint(no-restricted-imports)")).toBe(1);
    });

    it("rejects other components from components/ui but allows ui primitives", () => {
      expect(
        countIn("components/ui/app-component-import.ts", "eslint(no-restricted-imports)"),
      ).toBe(1);
    });

    it("allows the API client from shared code", () => {
      expect(codesIn("lib/clean.ts")).toStrictEqual([]);
    });

    it("allows the telemetry SDK only inside lib/telemetry.ts", () => {
      expect(codesIn("lib/telemetry.ts")).toStrictEqual([]);
    });
  });

  describe("UI", () => {
    describe("Base UI imports", () => {
      it("rejects Base UI outside components/ui", () => {
        expect(countIn("features/order/base-ui.tsx", "eslint(no-restricted-imports)")).toBe(1);
      });

      it("allows Base UI inside components/ui", () => {
        expect(codesIn("components/ui/primitive.tsx")).toStrictEqual([]);
      });

      it("keeps the shared layer and test-only bans inside components/ui", () => {
        expect(countIn("components/ui/shared-layer.tsx", "eslint(no-restricted-imports)")).toBe(2);
      });

      it("keeps Base UI banned in test files", () => {
        expect(countIn("features/order/base-ui.test.ts", "eslint(no-restricted-imports)")).toBe(1);
      });
    });

    describe("Tailwind classes", () => {
      it("rejects class names built from a value or picked by a key passed to a function", () => {
        expect(
          countIn("features/order/dynamic-class.tsx", "tailwind-classes(no-dynamic-class-name)"),
        ).toBe(5);
      });

      it("allows complete classes joined with a value or picked by a built key", () => {
        expect(codesIn("features/order/static-class.tsx")).toStrictEqual([]);
      });
    });

    describe("link and button composition", () => {
      it("rejects a button inside a link and a link passed to render of Button", () => {
        expect(
          countIn("features/order/button-in-link.tsx", "ui-composition(no-button-in-link)"),
        ).toBe(6);
      });

      it("allows a link beside a button", () => {
        expect(codesIn("features/order/link-beside-button.tsx")).toStrictEqual([]);
      });
    });
  });

  describe("Effect", () => {
    it("rejects named, aliased, member, destructured and re-exported Effect hooks", () => {
      expect(countIn("features/order/effect.tsx", "react-rules(no-effect)")).toBe(6);
    });

    it("allows other Hooks and a local object that has a useEffect property", () => {
      expect(codesIn("features/order/effect-clean.tsx")).toStrictEqual([]);
    });
  });

  describe("network access", () => {
    it("rejects fetch and XMLHttpRequest outside src/api", () => {
      expect(countIn("lib/network.ts", "eslint(no-restricted-globals)")).toBe(2);
      expect(countIn("lib/network.ts", "eslint(no-restricted-properties)")).toBe(2);
    });

    it("allows fetch inside src/api", () => {
      expect(codesIn("api/client.ts")).toStrictEqual([]);
    });
  });

  describe("HTML sink", () => {
    it("keeps DOMParser banned in every override", () => {
      expect(countIn("api/html-sink.ts", "eslint(no-restricted-globals)")).toBe(1);
      expect(countIn("lib/html-sink.ts", "eslint(no-restricted-globals)")).toBe(1);
    });
  });

  describe("test code", () => {
    it("rejects test-only modules from production code", () => {
      expect(countIn("components/msw-import.ts", "eslint(no-restricted-imports)")).toBe(4);
    });

    it("allows test-only modules in src/testing and test files", () => {
      expect(codesIn("testing/server.ts")).toStrictEqual([]);
      expect(codesIn("testing/render.ts")).toStrictEqual([]);
      expect(codesIn("features/order/order.test.ts")).toStrictEqual([]);
    });

    it("rejects module mocks with vi.mock and vi.doMock", () => {
      expect(
        countIn("features/order/module-mock.test.ts", "vitest(no-restricted-vi-methods)"),
      ).toBe(2);
    });

    it("allows replacing globals with vi.stubGlobal and vi.spyOn", () => {
      expect(codesIn("features/order/global-stub.test.ts")).toStrictEqual([]);
    });
  });

  // docs/tooling/lint-and-test.md の「Lintを通る書き方」の規則。
  describe("writing style", () => {
    describe("void", () => {
      it("allows void as a statement", () => {
        expect(codesIn("features/order/refresh-button.tsx")).toStrictEqual([]);
      });

      it("rejects void inside an expression", () => {
        expect(countIn("lib/void-expression.ts", "eslint(no-void)")).toBe(1);
      });
    });

    describe("undefined", () => {
      it("allows comparing with undefined", () => {
        expect(codesIn("lib/is-missing.ts")).toStrictEqual([]);
      });
    });

    describe("function style", () => {
      it("rejects arrow functions and function expressions assigned to a variable", () => {
        expect(countIn("lib/arrow-function.ts", "eslint(func-style)")).toBe(2);
      });

      it("rejects type-annotated function expressions", () => {
        expect(countIn("lib/typed-function-expression.ts", "eslint(func-style)")).toBe(1);
      });

      it("allows a type-annotated arrow function and arrow callbacks", () => {
        expect(codesIn("lib/typed-arrow-function.ts")).toStrictEqual([]);
      });
    });
  });

  describe("file name", () => {
    it("rejects PascalCase file names", () => {
      expect(countIn("features/order/OrderSummary.tsx", "unicorn(filename-case)")).toBe(1);
    });

    it("allows kebab-case file names", () => {
      expect(codesIn("features/order/order-summary.tsx")).toStrictEqual([]);
    });
  });
});
