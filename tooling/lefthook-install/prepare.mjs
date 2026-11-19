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
// postinstall は CI では動かず、失敗しても止まらないので、hook がこの worktree を指しているとは限らない。
const hooksMayPointHere = "lefthook の postinstall が、hook をこの worktree の lefthook に向けたままの可能性があります。";
const fail = (message) => {
  console.error(message);
  process.exit(1);
};

let main;
try {
  const [dir, common, top] = git("rev-parse", "--path-format=absolute", "--git-dir", "--git-common-dir", "--show-toplevel")
    .trim()
    .split("\n");
  main = top;
  // linked worktree からは、worktree list の最初の項目が main の worktree になる。bare のリポジトリでは、その項目に bare の行が付く。
  if (dir !== common) {
    const [first] = git("worktree", "list", "--porcelain").split("\n\n");
    if (/^bare$/m.test(first)) fail(`bare のリポジトリの worktree なので、main の worktree がありません。${hooksMayPointHere}`);
    main = first.match(/^worktree (.*)$/m)?.[1];
    if (main === undefined) fail(`git worktree list の最初の項目に main の worktree の場所がありません。${hooksMayPointHere}`);
  }
} catch (error) {
  if (/not a git repository/.test(error.stderr)) {
    console.log("git の作業ツリーの外なので、lefthook install を飛ばします。");
    process.exit(0);
  }
  process.stderr.write(error.stderr || `${error.message}\n`);
  fail(`git で worktree を調べられません。${hooksMayPointHere}`);
}
// --separate-git-dir の main の worktree は git に場所が残らず、最初の項目は git のディレクトリか、その末尾の /.git を除いた場所になる。
// どちらも checkout ではないので lefthook.yml が無い。lefthook install は lefthook.yml が無いと作るので、そこでは動かさない。
if (!existsSync(join(main, "lefthook.yml"))) {
  fail(
    `git が main の worktree として示す ${main} に lefthook.yml がありません。` +
      `lefthook.yml の無い commit を checkout しているか、--separate-git-dir などで git が checkout ではない場所を示している可能性があります。` +
      hooksMayPointHere,
  );
}
const bin = join(main, "node_modules", ".bin", "lefthook");
if (!existsSync(bin)) fail(`main の worktree（${main}）に lefthook がありません。${hooksMayPointHere}main の worktree で npm ci を実行すると直ります。`);
try {
  execFileSync(bin, ["install"], { cwd: main, stdio: "inherit" });
} catch {
  fail(`main の worktree（${main}）で lefthook install が失敗しました。${hooksMayPointHere}`);
}
