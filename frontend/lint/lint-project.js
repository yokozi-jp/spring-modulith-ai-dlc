import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, mkdtempSync, readdirSync, rmSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

/** @typedef {{ filename: string, code: string, message: string }} Diagnostic */

const frontendRoot = fileURLToPath(new URL("..", import.meta.url));

export const appDirectory = "apps/web";

/** @param {string} directory */
function copyProjectConfig(directory) {
  cpSync(
    path.join(frontendRoot, "lint/fixtures", appDirectory, "src"),
    path.join(directory, appDirectory, "src"),
    { recursive: true },
  );
  for (const file of ["vite.config.ts", "package.json"]) {
    cpSync(path.join(frontendRoot, file), path.join(directory, file));
  }
  for (const file of ["vite.config.ts", "tsconfig.json", "package.json", "src/style.css"]) {
    cpSync(path.join(frontendRoot, appDirectory, file), path.join(directory, appDirectory, file));
  }
  mkdirSync(path.join(directory, "lint"));
  /** @type {string[]} */
  const lintFiles = readdirSync(path.join(frontendRoot, "lint"));
  for (const file of lintFiles.filter((name) => /^[^.]+\.js$/u.test(name))) {
    cpSync(path.join(frontendRoot, "lint", file), path.join(directory, "lint", file));
  }
}

/** @param {string} directory */
function linkDependencies(directory) {
  for (const base of [".", appDirectory]) {
    symlinkSync(
      path.join(frontendRoot, base, "node_modules"),
      path.join(directory, base, "node_modules"),
    );
  }
}

/**
 * overrides の files は設定ファイルの場所からの相対パスで照合される。
 * そのため実際の設定と apps/web の設定を一時ディレクトリへ複製し、fixture をその apps/web/src に置いて lint する。
 * @param {string} cwd 一時ディレクトリからの相対パス。"." はルート、appDirectory はアプリの dir
 * @param {string} [target] 一時ディレクトリからの相対パス。省略すると fixture の全体を lint する
 * @returns {Diagnostic[]}
 */
export function lintFixtureProject(cwd, target = `${appDirectory}/src`) {
  const directory = mkdtempSync(path.join(tmpdir(), "frontend-lint-config-"));

  try {
    copyProjectConfig(directory);
    linkDependencies(directory);
    // vp lint をプロセスとして起動しプロジェクトの設定と plugin を読み込むため、所要時間は CPU の空きに比例する。
    // local-security.test.js と同じく、ハング検出として十分な 60 秒で打ち切る。
    /** @type {{ error?: Error, stdout: string }} */
    const { error, stdout } = spawnSync(
      "vp",
      ["lint", path.join(directory, target), "--format", "json"],
      {
        cwd: path.join(directory, cwd),
        encoding: "utf8",
        // 複製先に version.txt はない。シェルで有効にしていても、アプリの設定には無効を読ませる。
        env: { ...process.env, FRONTEND_OTEL_ENABLED: "false" },
        timeout: 60_000,
      },
    );
    if (error) {
      throw error;
    }
    /** @type {{ diagnostics: Diagnostic[] }} */
    const report = JSON.parse(stdout);
    return report.diagnostics;
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}
