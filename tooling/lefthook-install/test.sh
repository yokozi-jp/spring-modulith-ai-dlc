#!/usr/bin/env bash
# test.sh: 一時の git リポジトリと linked worktree で prepare.mjs を動かし、hook を入れる lefthook と、失敗を知らせる場面を確かめる。
# Run: bash tooling/lefthook-install/test.sh (task lefthook-install-test が実行する)
# ネイティブの Windows では動かさない（実行環境は WSL 上の Linux、ADR-010）。

set -u
. "$(dirname "$0")/../scratch-git/scratch-git.sh" || exit 1

root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
out="$tmp/out"
installed="$tmp/installed"
failures=0

# stub <ディレクトリ> <名前> [<sh のコマンド>]: lefthook の代わりに、名前と動いたディレクトリを記録してから <sh のコマンド> を動かすスクリプトを置く。
stub() {
  mkdir -p "$1/node_modules/.bin"
  printf '#!/bin/sh\necho "%s $(pwd -P)" >>"%s"\n%s\n' "$2" "$installed" "${3:-}" >"$1/node_modules/.bin/lefthook"
  chmod +x "$1/node_modules/.bin/lefthook"
}

# prepare <ディレクトリ>: npm と同じく、そのディレクトリの node_modules/.bin を PATH の先頭に置いて prepare を動かし、終了コードを status に置く。
prepare() {
  : >"$installed"
  (cd "$1" && PATH="$1/node_modules/.bin:$PATH" node "$root/tooling/lefthook-install/prepare.mjs") >"$out" 2>&1
  status=$?
}

# fact <説明> <コマンド>...: コマンドが失敗したら、説明と、ログを字下げして出す。
# ログは最後の prepare の出力（$out）で、prepare を動かさない事実は log=<ファイル> を前に付けて自分のログを渡す。
fact() {
  local label="$1"
  shift
  if ! "$@"; then
    echo "FAIL: $label"
    sed 's/^/    /' "${log:-$out}"
    failures=$((failures + 1))
  fi
}

real() { (cd "$1" && pwd -P); }
succeeded() { [ "$status" -eq 0 ]; }
failed() { [ "$status" -ne 0 ]; }
installed_by() { [ "$(cat "$installed")" = "$1 $(real "$2")" ]; }
nothing_installed() { [ ! -s "$installed" ]; }
said() { grep -qF -- "$1" "$out"; }
no_stack_trace() { ! grep -q '^    at ' "$out"; }
# prepare.mjs が失敗を知らせるメッセージの末尾。prepare.mjs がメッセージを組み立てる行で落ちると、node はその行のソースを出す。
# ソースに書いたままの文字列だけでは落ちたことと見分けられないので、組み立てた後にしか出ない部分まで比べる。
may_point_here="lefthook の postinstall が、hook をこの worktree の lefthook に向けたままの可能性があります。"
dump_log="$tmp/dump"
auto_install_off() {
  local dump
  dump="$(cd "$root" && node_modules/.bin/lefthook dump --format json 2>"$dump_log")" ||
    { echo "lefthook dump が終了コード $? で失敗したので、lefthook.yml を読めません。" >>"$dump_log"; return 1; }
  node -e 'process.exit(JSON.parse(require("fs").readFileSync(0, "utf8")).no_auto_install === true ? 0 : 1)' <<<"$dump"
}

# store と bx は checkout ではない場所で、git が main の worktree の代わりに出す。そこの lefthook が動けば記録に残る。
{
  git init -q -b main "$tmp/repo" &&
    git -C "$tmp/repo" commit -q --allow-empty -m init &&
    git -C "$tmp/repo" worktree add -q "$tmp/wt" &&
    git init -q -b main --separate-git-dir "$tmp/sep.git" "$tmp/sep" &&
    git -C "$tmp/sep" commit -q --allow-empty -m init &&
    git -C "$tmp/sep" worktree add -q "$tmp/sep-wt" &&
    mkdir "$tmp/store" &&
    git init -q -b main --separate-git-dir "$tmp/store/.git" "$tmp/store-main" &&
    git -C "$tmp/store-main" commit -q --allow-empty -m init &&
    git -C "$tmp/store-main" worktree add -q "$tmp/store-wt" &&
    git clone -q --bare "$tmp/repo" "$tmp/bare.git" &&
    git -C "$tmp/bare.git" worktree add -q "$tmp/bare-wt" &&
    git clone -q --bare "$tmp/repo" "$tmp/bx/.git" &&
    git -C "$tmp/bx/.git" worktree add -q "$tmp/bx-wt" &&
    git init -q -b main "$tmp/old" &&
    git -C "$tmp/old" commit -q --allow-empty -m init &&
    git -C "$tmp/old" worktree add -q "$tmp/old-wt" &&
    git -C "$tmp/repo" worktree add -q "$tmp/shim-wt" &&
    git init -q "$tmp/broken" &&
    printf '[[[\n' >>"$tmp/broken/.git/config" &&
    mkdir "$tmp/outside"
} || { echo "FAIL: 一時のリポジトリを作れません"; exit 1; }
for dir in repo sep store bx old; do stub "$tmp/$dir" "$dir"; done
for dir in wt sep-wt store-wt bare-wt bx-wt; do stub "$tmp/$dir" worktree; done
touch "$tmp/repo/lefthook.yml" "$tmp/sep/lefthook.yml" "$tmp/bx/lefthook.yml"

prepare "$tmp/wt"
fact "a linked worktree succeeds" succeeded
fact "a linked worktree installs the hooks with the main worktree's lefthook, run in the main worktree" installed_by repo "$tmp/repo"

prepare "$tmp/repo"
fact "the main worktree succeeds" succeeded
fact "the main worktree installs the hooks with its own lefthook" installed_by repo "$tmp/repo"

prepare "$tmp/sep"
fact "a main worktree with --separate-git-dir succeeds" succeeded
fact "a main worktree with --separate-git-dir installs the hooks with its own lefthook" installed_by sep "$tmp/sep"

prepare "$tmp/sep-wt"
fact "a linked worktree of --separate-git-dir fails because git names the git directory, which is not a checkout" failed
fact "a linked worktree of --separate-git-dir says the place git names has no lefthook.yml" said "git が main の worktree として示す $(real "$tmp/sep.git") に lefthook.yml がありません。"
fact "a linked worktree of --separate-git-dir installs nothing" nothing_installed

prepare "$tmp/store-wt"
fact "a linked worktree of --separate-git-dir <dir>/.git fails because git names <dir>, which is not a checkout" failed
fact "a linked worktree of --separate-git-dir <dir>/.git says <dir> has no lefthook.yml" said "git が main の worktree として示す $(real "$tmp/store") に lefthook.yml がありません。"
fact "a linked worktree of --separate-git-dir <dir>/.git does not run the lefthook in <dir>" nothing_installed

prepare "$tmp/old-wt"
fact "a linked worktree fails if the main worktree has no lefthook.yml (checked out on an old commit)" failed
fact "a linked worktree says the main worktree has no lefthook.yml" said "git が main の worktree として示す $(real "$tmp/old") に lefthook.yml がありません。"
fact "a linked worktree does not run the lefthook in a main worktree without lefthook.yml" nothing_installed

prepare "$tmp/bare-wt"
fact "a linked worktree of a bare repository fails because there is no main worktree" failed
fact "a linked worktree of a bare repository says it is a worktree of a bare repository" said "bare のリポジトリの worktree なので、main の worktree がありません。$may_point_here"
fact "a linked worktree of a bare repository installs nothing" nothing_installed

prepare "$tmp/bx-wt"
fact "a linked worktree of a bare repository at <dir>/.git fails even if <dir> has lefthook.yml" failed
fact "a linked worktree of a bare repository at <dir>/.git says it is a worktree of a bare repository" said "bare のリポジトリの worktree なので、main の worktree がありません。$may_point_here"
fact "a linked worktree of a bare repository at <dir>/.git does not run the lefthook in <dir>" nothing_installed

# git_shim <sh のコマンド>: shim-wt の git を、git worktree のときだけ <sh のコマンド> を動かし、ほかは本物の git に渡すものにする。
real_git="$(command -v git)"
git_shim() {
  mkdir -p "$tmp/shim-wt/node_modules/.bin"
  printf '#!/bin/sh\n[ "$1" = worktree ] && { %s; }\nexec "%s" "$@"\n' "$1" "$real_git" >"$tmp/shim-wt/node_modules/.bin/git"
  chmod +x "$tmp/shim-wt/node_modules/.bin/git"
}

git_shim 'echo "fatal: worktree list のおとり" >&2; exit 128'
prepare "$tmp/shim-wt"
fact "a failing git worktree list fails" failed
fact "a failing git worktree list shows git's message" said "fatal: worktree list のおとり"
fact "a failing git worktree list says git could not inspect the worktrees" said "git で worktree を調べられません。$may_point_here"
fact "a failing git worktree list installs nothing" nothing_installed

git_shim 'exit 0'
prepare "$tmp/shim-wt"
fact "git worktree list without a worktree line fails" failed
fact "git worktree list without a worktree line says so" said "git worktree list の最初の項目に main の worktree の場所がありません。$may_point_here"
fact "git worktree list without a worktree line installs nothing" nothing_installed

# prepare.mjs の LC_ALL=C は、次の 2 つの場面で git のメッセージを英語に固定する。
# CI には git のメッセージを翻訳するロケールが無いので、LC_ALL=C を外してもこの 2 つは失敗しない。
# 翻訳されたメッセージの場面は CI で作れないので、足していない。
prepare "$tmp/outside"
fact "outside a git repository it succeeds" succeeded
fact "outside a git repository it installs nothing" nothing_installed

prepare "$tmp/broken"
fact "a git error other than being outside a repository fails" failed
fact "a git error other than being outside a repository shows git's message" said "bad config line"
fact "a git error other than being outside a repository says git could not inspect the worktrees" said "git で worktree を調べられません。$may_point_here"
fact "a git error other than being outside a repository installs nothing" nothing_installed

mv "$tmp/repo/node_modules" "$tmp/moved"
prepare "$tmp/wt"
fact "without lefthook in the main worktree a linked worktree fails" failed
fact "without lefthook in the main worktree it installs nothing" nothing_installed
fact "without lefthook in the main worktree it says to run npm ci" said "npm ci"
fact "without lefthook in the main worktree it names the main worktree" said "$(real "$tmp/repo")"

stub "$tmp/repo" repo 'echo "lefthook install のおとり" >&2; exit 3'
prepare "$tmp/wt"
fact "a failing lefthook install fails" failed
fact "a failing lefthook install shows lefthook's message" said "lefthook install のおとり"
fact "a failing lefthook install says the hooks may still point at this worktree" said "main の worktree（$(real "$tmp/repo")）で lefthook install が失敗しました。$may_point_here"
fact "a failing lefthook install shows no stack trace" no_stack_trace

log="$dump_log" fact "lefthook run does not reinstall the hooks (no_auto_install in lefthook.yml)" auto_install_off

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
