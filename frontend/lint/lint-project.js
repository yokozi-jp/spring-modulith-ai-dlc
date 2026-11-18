import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, mkdtempSync, readdirSync, rmSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

/** @typedef {{ filename: string, code: string, message: string }} Diagnostic */

const frontendRoot = fileURLToPath(new URL("..", import.meta.url));

export const appDirectory = "apps/web";

// lint/fixtures の下に fixture を持つ package と、その設定のために複製するファイル。
// ponytail: package の一覧と複製するファイルを手で書く。package が増えて手間になったら、lint/fixtures の下を巡る形にする。
/** @type {Record<string, string[]>} */
const packageFiles = {
  [appDirectory]: ["vite.config.ts", "tsconfig.json", "package.json", "src/style.css"],
  "packages/api": ["tsconfig.json", "package.json"],
};

/** @param {string} directory */
function copyProjectConfig(directory) {
  for (const file of ["vite.config.ts", "package.json", "tsconfig.base.json"]) {
    cpSync(path.join(frontendRoot, file), path.join(directory, file));
  }
  for (const [packageDirectory, files] of Object.entries(packageFiles)) {
    cpSync(
      path.join(frontendRoot, "lint/fixtures", packageDirectory, "src"),
      path.join(directory, packageDirectory, "src"),
      { recursive: true },
    );
    for (const file of files) {
      cpSync(
        path.join(frontendRoot, packageDirectory, file),
        path.join(directory, packageDirectory, file),
      );
    }
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
  for (const base of [".", ...Object.keys(packageFiles)]) {
    symlinkSync(
      path.join(frontendRoot, base, "node_modules"),
      path.join(directory, base, "node_modules"),
    );
  }
}

/**
 * @param {string} directory 複製した frontend/
 * @param {string} cwd directory からの相対パス
 * @param {string[]} targets directory からの相対パス
 * @returns {Diagnostic[]}
 */
function runLint(directory, cwd, targets) {
  // vp lint をプロセスとして起動しプロジェクトの設定と plugin を読み込むため、所要時間は CPU の空きに比例する。
  // local-security.test.js と同じく、ハング検出として十分な 60 秒で打ち切る。
  /** @type {{ error?: Error, status: number | null, stderr: string, stdout: string }} */
  const { error, status, stderr, stdout } = spawnSync(
    "vp",
    ["lint", ...targets.map((target) => path.join(directory, target)), "--format", "json"],
    {
      cwd: path.join(directory, cwd),
      encoding: "utf8",
      // 複製先に version.txt はない。シェルで有効にしていても、アプリの設定には無効を読ませる。
      // better-tailwindcss は class の照会を worker に任せ、30 秒で打ち切る。負荷が高いと、打ち切られた規則は診断を出さずに通る。
      env: { ...process.env, FRONTEND_OTEL_ENABLED: "false", SYNCKIT_TIMEOUT: "60000" },
      timeout: 60_000,
    },
  );
  if (error) {
    throw error;
  }
  if (stdout === "") {
    throw new Error(`vp lint が結果を出しませんでした（終了コード ${status}）。${stderr}`);
  }
  /** @type {{ diagnostics: Diagnostic[] }} */
  const report = JSON.parse(stdout);
  const pluginError = report.diagnostics.find(({ message }) =>
    message.startsWith("Error running JS plugin"),
  );
  if (pluginError) {
    throw new Error(`JS plugin が失敗しました。${pluginError.filename}: ${pluginError.message}`);
  }
  return report.diagnostics;
}

/**
 * overrides の files は設定ファイルの場所からの相対パスで照合される。
 * そのため実際の設定と各 package の設定を一時ディレクトリへ複製し、fixture をその package の src に置いて lint する。
 * @param {string} cwd 一時ディレクトリからの相対パス。"." はルート、appDirectory はアプリの dir
 * @param {string[]} [targets] 一時ディレクトリからの相対パス。省略すると全 package の fixture を lint する
 * @returns {Diagnostic[]}
 */
export function lintFixtureProject(
  cwd,
  targets = Object.keys(packageFiles).map((packageDirectory) => `${packageDirectory}/src`),
) {
  const temporary = mkdtempSync(path.join(tmpdir(), "frontend-lint-config-"));
  // アプリの設定は、リポジトリ直下に見立てた 3 つ上の .env を読む。frontend/ の 1 つ上を空の一時ディレクトリにして、/tmp の .env を読ませない。
  const directory = path.join(temporary, "frontend");
  mkdirSync(directory);

  try {
    copyProjectConfig(directory);
    linkDependencies(directory);
    return runLint(directory, cwd, targets);
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
}
