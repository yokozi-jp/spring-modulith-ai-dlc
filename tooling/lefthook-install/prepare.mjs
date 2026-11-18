#!/usr/bin/env node
// prepare.mjs: npm の prepare から呼ばれ、hook を main の worktree の lefthook で入れ直す。
// Run: node tooling/lefthook-install/prepare.mjs（npm ci と npm install の prepare が実行する）
// git の hook は linked worktree も含めてリポジトリで一つで、lefthook install は入れた lefthook の絶対パスを hook に書く。
// lefthook の npm パッケージの postinstall は、prepare より先に、この worktree の lefthook で hook を入れる。
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";

// git のメッセージの言語を固定し、git のリポジトリの外かどうかをメッセージで見分ける。
const git = (...args) =>
  execFileSync("git", args, { encoding: "utf8", env: { ...process.env, LC_ALL: "C" }, stdio: ["ignore", "pipe", "pipe"] });
const hooksPointHere = "lefthook の postinstall が、hook をこの worktree の lefthook に向けたままです。";

let dir, common, top;
try {
  [dir, common, top] = git("rev-parse", "--path-format=absolute", "--git-dir", "--git-common-dir", "--show-toplevel")
    .trim()
    .split("\n");
} catch (error) {
  if (/not a git repository/.test(error.stderr)) {
    console.log("git の作業ツリーの外なので、lefthook install を飛ばします。");
    process.exit(0);
  }
  process.stderr.write(error.stderr || `${error.message}\n`);
  console.error(`git で worktree を調べられません。${hooksPointHere}`);
  process.exit(1);
}
// linked worktree からは、worktree list の最初の項目が main の worktree になる。bare のリポジトリでは、その項目に bare の行が付く。
let main = top;
if (dir !== common) {
  const [first] = git("worktree", "list", "--porcelain").split("\n\n");
  main = /^bare$/m.test(first) ? undefined : first.match(/^worktree (.*)$/m)[1];
}
// --separate-git-dir の main の worktree は git に場所が残らず、最初の項目は git のディレクトリか、その末尾の /.git を除いた場所になる。
// どちらも checkout ではないので lefthook.yml が無い。lefthook install は lefthook.yml が無いと作るので、そこでは動かさない。
if (main === undefined || !existsSync(join(main, "lefthook.yml"))) {
  console.error(`git から main の worktree の場所が分かりません（bare のリポジトリか --separate-git-dir）。${hooksPointHere}`);
  process.exit(1);
}
const bin = join(main, "node_modules", ".bin", "lefthook");
if (!existsSync(bin)) {
  console.error(`main の worktree（${main}）に lefthook がありません。${hooksPointHere}main の worktree で npm ci を実行すると直ります。`);
  process.exit(1);
}
execFileSync(bin, ["install"], { cwd: main, stdio: "inherit" });
