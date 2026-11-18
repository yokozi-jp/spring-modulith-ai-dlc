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
      // 複製先に version.txt はない。シェルで有効にしていても、無効の設定を読ませる。
      env: { ...process.env, FRONTEND_OTEL_ENABLED: "false" },
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

    it("api から components の import を禁止する", () => {
      expect(countIn("api/component-import.ts", "eslint(no-restricted-imports)")).toBe(1);
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
    it("src/api の外では fetch と XMLHttpRequest を禁止する", () => {
      expect(countIn("lib/network.ts", "eslint(no-restricted-globals)")).toBe(2);
      expect(countIn("lib/network.ts", "eslint(no-restricted-properties)")).toBe(2);
    });

    it("src/api では fetch を許可する", () => {
      expect(codesIn("api/client.ts")).toStrictEqual([]);
    });
  });

  describe("HTML sink の制限", () => {
    it("どの override でも DOMParser を禁止する", () => {
      expect(countIn("api/html-sink.ts", "eslint(no-restricted-globals)")).toBe(1);
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
