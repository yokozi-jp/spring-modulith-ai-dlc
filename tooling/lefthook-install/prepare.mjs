#!/usr/bin/env node
// prepare.mjs: npm の prepare から呼ばれ、hook を main の作業ツリーの lefthook で入れ直す。
// Run: node tooling/lefthook-install/prepare.mjs（npm ci と npm install の prepare が実行する）
// git の hook は linked worktree も含めてリポジトリで一つなので、worktree の lefthook で入れると、hook がその worktree を指す。
// lefthook の npm パッケージの postinstall も lefthook install を実行するので、依存を入れた後に走る prepare で上書きする。
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { basename, dirname, join } from "node:path";

const git = (...args) =>
  execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();

let dir;
let common;
try {
  [dir, common] = git("rev-parse", "--path-format=absolute", "--git-dir", "--git-common-dir").split("\n");
} catch {
  console.log("git の作業ツリーの外なので、lefthook install を飛ばします。");
  process.exit(0);
}
if (dir === common) {
  execFileSync("lefthook", ["install"], { stdio: "inherit" });
  process.exit(0);
}
const main = basename(common) === ".git" ? dirname(common) : undefined;
const bin = main && join(main, "node_modules", ".bin", "lefthook");
if (!bin || !existsSync(bin)) {
  console.log(`main の作業ツリーに lefthook がありません。main の作業ツリーで npm ci を実行してください（${common}）。`);
  process.exit(0);
}
execFileSync(bin, ["install"], { cwd: main, stdio: "inherit" });
