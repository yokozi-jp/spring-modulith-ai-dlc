import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { describe, expect, it, vi } from "vite-plus/test";

import { noJsxSrcDoc } from "./local-security.js";

function checkAttribute(name) {
  const report = vi.fn();
  const visitor = noJsxSrcDoc.create({ report });

  visitor.JSXAttribute({ name: { type: "JSXIdentifier", name } });

  return report;
}

describe("local-security/no-jsx-srcdoc", () => {
  it("rejects srcDoc", () => {
    expect(checkAttribute("srcDoc")).toHaveBeenCalledOnce();
  });

  it("allows ordinary JSX attributes", () => {
    expect(checkAttribute("src")).not.toHaveBeenCalled();
  });
});

describe("HTML sink restrictions", () => {
  it("rejects qualified DOMParser access through the project lint config", () => {
    const directory = mkdtempSync(path.join(tmpdir(), "frontend-security-lint-"));
    const fixture = path.join(directory, "qualified-dom-parser.ts");

    try {
      writeFileSync(
        fixture,
        'export const parseHtml = (input: string) => new window.DOMParser().parseFromString(input, "text/html");\n',
      );
      const result = spawnSync("vp", ["lint", fixture, "--no-ignore"], {
        cwd: fileURLToPath(new URL("..", import.meta.url)),
        encoding: "utf8",
      });

      expect(result.error).toBeUndefined();
      expect(result.status).not.toBe(0);
      expect(`${result.stdout}\n${result.stderr}`).toContain("no-restricted-properties");
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
    // vp lint をプロセスとして起動しプロジェクトの設定と plugin を読み込むため、所要時間は CPU の空きに比例する。
    // pre-push の並列実行では 15 秒を超えたので、ハング検出として十分な 60 秒にする。
  }, 60_000);
});
