import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, mkdtempSync, readdirSync, rmSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vite-plus/test";

const frontendRoot = fileURLToPath(new URL("..", import.meta.url));

/** @param {string} directory */
function copyProjectConfig(directory) {
  cpSync(path.join(frontendRoot, "lint/fixtures/src"), path.join(directory, "src"), {
    recursive: true,
  });
  for (const file of ["vite.config.ts", "tsconfig.json", "package.json", "src/style.css"]) {
    cpSync(path.join(frontendRoot, file), path.join(directory, file));
  }
  mkdirSync(path.join(directory, "lint"));
  /** @type {string[]} */
  const lintFiles = readdirSync(path.join(frontendRoot, "lint"));
  for (const file of lintFiles.filter((name) => /^[^.]+\.js$/u.test(name))) {
    cpSync(path.join(frontendRoot, "lint", file), path.join(directory, "lint", file));
  }
  symlinkSync(path.join(frontendRoot, "node_modules"), path.join(directory, "node_modules"));
}

// overrides の files は設定ファイルの場所からの相対パスで照合される。
// そのため実際の設定を一時ディレクトリへ複製し、fixture をその src/ に置いて lint する。
/** @returns {{ filename: string, code: string }[]} */
function lintFixtures() {
  /** @type {string} */
  const directory = mkdtempSync(path.join(tmpdir(), "frontend-lint-config-"));

  try {
    copyProjectConfig(directory);
    // vp lint をプロセスとして起動しプロジェクトの設定と plugin を読み込むため、所要時間は CPU の空きに比例する。
    // local-security.test.js と同じく、ハング検出として十分な 60 秒で打ち切る。
    /** @type {{ error?: Error, stdout: string }} */
    const { error, stdout } = spawnSync("vp", ["lint", "src", "--format", "json"], {
      cwd: directory,
      encoding: "utf8",
      timeout: 60_000,
    });
    if (error) {
      throw error;
    }
    /** @type {{ diagnostics: { filename: string, code: string }[] }} */
    const report = JSON.parse(stdout);
    return report.diagnostics;
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

const diagnostics = lintFixtures();

/** @param {string} file */
function codesIn(file) {
  return diagnostics
    .filter((diagnostic) => diagnostic.filename === `src/${file}`)
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
      expect(countIn("features/order/telemetry-sdk.ts", "eslint(no-restricted-imports)")).toBe(1);
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

    it("allows the API client from shared code", () => {
      expect(codesIn("lib/clean.ts")).toStrictEqual([]);
    });

    it("allows the telemetry SDK only inside lib/telemetry.ts", () => {
      expect(codesIn("lib/telemetry.ts")).toStrictEqual([]);
    });
  });

  describe("Base UI", () => {
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

  describe("file name", () => {
    it("rejects PascalCase file names", () => {
      expect(countIn("features/order/OrderSummary.tsx", "unicorn(filename-case)")).toBe(1);
    });

    it("allows kebab-case file names", () => {
      expect(codesIn("features/order/order-summary.tsx")).toStrictEqual([]);
    });
  });
});
