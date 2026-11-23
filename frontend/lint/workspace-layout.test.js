import { spawnSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { loadConfigFromFile } from "vite-plus";
import { describe, expect, it } from "vite-plus/test";

const frontendRoot = fileURLToPath(new URL("..", import.meta.url));

/** @param {string} parent frontend/ からの相対パス */
function directoriesIn(parent) {
  return readdirSync(path.join(frontendRoot, parent), { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => `${parent}/${entry.name}`);
}

const apps = directoriesIn("apps");
const packages = directoriesIn("packages");
const everyPackage = [...apps, ...packages];

/** @param {string} directory */
function configOf(directory) {
  return path.join(frontendRoot, directory, "vite.config.ts");
}

/** @type {Array<"dependencies" | "devDependencies" | "peerDependencies" | "optionalDependencies">} */
const dependencyFields = [
  "dependencies",
  "devDependencies",
  "peerDependencies",
  "optionalDependencies",
];

/** @param {string} directory */
function manifestOf(directory) {
  /** @type {{ name: string, exports?: Record<string, unknown> } & Partial<Record<(typeof dependencyFields)[number], Record<string, string>>>} */
  const manifest = JSON.parse(
    readFileSync(path.join(frontendRoot, directory, "package.json"), "utf8"),
  );
  return manifest;
}

// vp や pnpm の shim は NODE_PATH に .pnpm/node_modules を足し、宣言のない workspace package も解決できてしまう。NODE_PATH を空にして、素の Node の解決で確かめる。
const resolveScript = `
const { createRequire } = require("node:module");
try {
  createRequire(process.argv[1]).resolve(process.argv[2]);
  process.stdout.write("RESOLVED");
} catch (error) {
  process.stdout.write(error.code);
}
`;

// exports を持つ package は、公開した最初の subpath で解決する。exports がなければ package.json で解決する。
// exports にない subpath は、依存を書いていても MODULE_NOT_FOUND になり、宣言の有無を区別できない。
/** @param {string} directory */
function entryOf(directory) {
  const { name, exports } = manifestOf(directory);
  const subpath = Object.keys(exports ?? {}).find((key) => !key.includes("*"));
  return `${name}${subpath?.slice(1) ?? "/package.json"}`;
}

/**
 * from の package から to の package を import したときの結果。
 * package.json に依存を書かない package は MODULE_NOT_FOUND になる。
 * @param {string} from
 * @param {string} to
 */
function resolutionOf(from, to) {
  const { error, stdout } = spawnSync(
    process.execPath,
    ["-e", resolveScript, path.join(frontendRoot, from, "package.json"), entryOf(to)],
    { encoding: "utf8", env: { ...process.env, NODE_PATH: "" }, timeout: 60_000 },
  );
  if (error) {
    throw error;
  }
  return stdout;
}

// アプリが ui と api を使うことだけを許す。それ以外は package.json に依存を書かないことで止める（ADR-092）。
const forbiddenImports = everyPackage.flatMap((from) =>
  everyPackage
    .filter((to) => to !== from && !(from.startsWith("apps/") && packages.includes(to)))
    .map((to) => [from, to]),
);

describe("workspace の層の規則", () => {
  describe("設定の置き場", () => {
    // vite.config.ts を持たない package は、lint と fmt を持たないので対象外にする。
    it.each(everyPackage.filter((directory) => existsSync(configOf(directory))))(
      "%s の vite.config.ts に lint と fmt がない",
      async (directory) => {
        const loaded = await loadConfigFromFile(
          { command: "serve", mode: "test" },
          configOf(directory),
        );

        expect(loaded?.config).not.toHaveProperty("lint");
        expect(loaded?.config).not.toHaveProperty("fmt");
      },
    );

    // 新しい package が vite.config.ts に perFile を書き忘れても、coverage は黙って通る（ADR-076）。
    it.each(everyPackage)("%s の coverage にファイルごとの下限がある", async (directory) => {
      const loaded = await loadConfigFromFile(
        { command: "serve", mode: "test" },
        configOf(directory),
      );

      expect(loaded?.config.test?.coverage).toMatchObject({
        thresholds: { perFile: { branches: 60, lines: 65 } },
      });
    });

    it.each(apps)("%s の style.css が packages/ui/src を @source で走査する", (directory) => {
      const css = readFileSync(path.join(frontendRoot, directory, "src/style.css"), "utf8");
      const sources = [...css.matchAll(/^@source\s+["'](?<source>[^"']+)["']/gmu)].map((match) =>
        path.resolve(frontendRoot, directory, "src", String(match.groups?.source)),
      );

      expect(sources).toContain(path.join(frontendRoot, "packages/ui/src"));
    });

    // 足し忘れた部品は、中身が空でも jsx-a11y が報告しない。素の要素を包まない部品を足したら、ここで除く。
    it("packages/ui の部品が jsx-a11y の components で素の要素に対応づいている", async () => {
      const uiSource = path.join(frontendRoot, "packages/ui/src");
      const files = readdirSync(uiSource, { recursive: true, encoding: "utf8" }).filter(
        (file) => file.endsWith(".tsx") && !file.includes(".test."),
      );
      const keyLists = await Promise.all(
        files.map(async (file) => {
          /** @type {Record<string, unknown>} */
          const loadedModule = await import(path.join(uiSource, file));
          return Object.keys(loadedModule);
        }),
      );
      const exported = keyLists.flat().filter((name) => /^[A-Z]/u.test(name));
      const loaded = await loadConfigFromFile(
        { command: "serve", mode: "test" },
        path.join(frontendRoot, "vite.config.ts"),
      );
      const components = loaded?.config.lint?.settings?.["jsx-a11y"]?.components ?? {};

      expect(exported).toContain("Button");
      expect(
        Object.fromEntries(exported.map((name) => [name, components[name]])),
        "vite.config.ts の lint.settings の jsx-a11y.components に、部品の名前と包む要素を足す",
      ).toStrictEqual(
        Object.fromEntries(exported.map((name) => [name, expect.stringMatching(/^[a-z]+$/u)])),
      );
    });
  });

  describe("package の参照", () => {
    it("アプリは package.json に書いた @frontend/ui と @frontend/api を解決できる", () => {
      expect(resolutionOf("apps/web", "packages/ui")).toBe("RESOLVED");
      expect(resolutionOf("apps/web", "packages/api")).toBe("RESOLVED");
    });

    it.each(forbiddenImports)("%s は package.json に書かない %s を解決できない", (from, to) => {
      expect(resolutionOf(from, to)).toBe("MODULE_NOT_FOUND");
    });
  });
});

describe("依存の版", () => {
  // 版を package ごとに書くと、Dependabot のセキュリティ更新が片方の package だけを上げて版が割れる（ADR-100）。
  it("2 つ以上の package.json にある依存は catalog: で版を 1 つにする", () => {
    const declarations = [".", ...everyPackage].flatMap((directory) => {
      const manifest = manifestOf(directory);
      const file = path.join(directory, "package.json");
      return dependencyFields.flatMap((field) =>
        Object.entries(manifest[field] ?? {}).map(([name, spec]) => ({ file, name, spec })),
      );
    });
    /** @type {Map<string, Set<string>>} */
    const filesByName = new Map();
    for (const { file, name } of declarations) {
      filesByName.set(name, (filesByName.get(name) ?? new Set()).add(file));
    }
    const pinned = declarations
      .filter(
        ({ name, spec }) =>
          (filesByName.get(name)?.size ?? 0) > 1 &&
          spec !== "catalog:" &&
          !spec.startsWith("workspace:"),
      )
      .map(({ file, name, spec }) => `${file}: ${name}@${spec}`);

    expect(
      pinned,
      '版を pnpm-workspace.yaml の catalog に移し、package.json には "catalog:" と書く',
    ).toStrictEqual([]);
  });
});
