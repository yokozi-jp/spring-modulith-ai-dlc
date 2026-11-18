#!/usr/bin/env node
// prepare.mjs: npm の prepare から呼ばれ、hook を main の worktree の lefthook で入れ直す。
// Run: node tooling/lefthook-install/prepare.mjs（npm ci と npm install の prepare が実行する）
// git の hook は linked worktree も含めてリポジトリで一つで、lefthook install は入れた lefthook の絶対パスを hook に書く。
// lefthook の npm パッケージの postinstall は、prepare より先に、この worktree の lefthook で hook を入れる。
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";

const git = (...args) => execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });

let dir, common, top;
try {
  [dir, common, top] = git("rev-parse", "--path-format=absolute", "--git-dir", "--git-common-dir", "--show-toplevel")
    .trim()
    .split("\n");
} catch {
  console.log("git の作業ツリーの外なので、lefthook install を飛ばします。");
  process.exit(0);
}
// linked worktree からは、worktree list の最初の項目が main の worktree になる。
// main が bare か --separate-git-dir のとき、git は main の worktree の場所を持たず、最初の項目に git のディレクトリを出す。
const main = dir === common ? top : git("worktree", "list", "--porcelain").match(/^worktree (.*)$/m)[1];
const bin = join(main, "node_modules", ".bin", "lefthook");
const stale = "lefthook の postinstall が、hook をこの worktree の lefthook に向けたままです。";
if (main === common) {
  console.error(`git から main の worktree の場所が分かりません（bare のリポジトリか --separate-git-dir）。${stale}`);
  process.exit(1);
}
if (!existsSync(bin)) {
  console.error(`main の worktree（${main}）に lefthook がありません。${stale}main の worktree で npm ci を実行すると直ります。`);
  process.exit(1);
}
execFileSync(bin, ["install"], { cwd: main, stdio: "inherit" });
