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

# stub <ディレクトリ> <名前>: lefthook の代わりに、名前と動いたディレクトリを記録するスクリプトを置く。
stub() {
  mkdir -p "$1/node_modules/.bin"
  printf '#!/bin/sh\necho "%s $(pwd -P)" >>"%s"\n' "$2" "$installed" >"$1/node_modules/.bin/lefthook"
  chmod +x "$1/node_modules/.bin/lefthook"
}

# prepare <ディレクトリ>: npm と同じく、そのディレクトリの node_modules/.bin を PATH の先頭に置いて prepare を動かし、終了コードを status に置く。
prepare() {
  : >"$installed"
  (cd "$1" && PATH="$1/node_modules/.bin:$PATH" node "$root/tooling/lefthook-install/prepare.mjs") >"$out" 2>&1
  status=$?
}

fact() {
  local label="$1"
  shift
  if ! "$@"; then
    echo "FAIL: $label"
    sed 's/^/    /' "$out"
    failures=$((failures + 1))
  fi
}

succeeded() { [ "$status" -eq 0 ]; }
failed() { [ "$status" -ne 0 ]; }
installed_by() { [ "$(cat "$installed")" = "$1 $(cd "$2" && pwd -P)" ]; }
nothing_installed() { [ ! -s "$installed" ]; }
said() { grep -qF -- "$1" "$out"; }
not() { ! "$@"; }
auto_install_off() {
  local dump
  dump="$(cd "$root" && node_modules/.bin/lefthook dump --format json 2>"$out")" ||
    { echo "lefthook dump が終了コード $? で失敗したので、lefthook.yml を読めません。" >>"$out"; return 1; }
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
    git init -q "$tmp/broken" &&
    printf '[[[\n' >>"$tmp/broken/.git/config" &&
    mkdir "$tmp/outside"
} || { echo "FAIL: 一時のリポジトリを作れません"; exit 1; }
for dir in repo sep store bx; do stub "$tmp/$dir" "$dir"; done
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
fact "a linked worktree of --separate-git-dir fails because git does not know the main worktree" failed
fact "a linked worktree of --separate-git-dir installs nothing" nothing_installed
fact "a linked worktree of --separate-git-dir does not point at npm ci in a main worktree" not said "npm ci"

prepare "$tmp/store-wt"
fact "a linked worktree of --separate-git-dir <dir>/.git fails because git names <dir>, which is not a checkout" failed
fact "a linked worktree of --separate-git-dir <dir>/.git does not run the lefthook in <dir>" nothing_installed
fact "a linked worktree of --separate-git-dir <dir>/.git does not point at npm ci in a main worktree" not said "npm ci"

prepare "$tmp/bare-wt"
fact "a linked worktree of a bare repository fails because there is no main worktree" failed
fact "a linked worktree of a bare repository installs nothing" nothing_installed
fact "a linked worktree of a bare repository does not point at npm ci in a main worktree" not said "npm ci"

prepare "$tmp/bx-wt"
fact "a linked worktree of a bare repository at <dir>/.git fails even if <dir> has lefthook.yml" failed
fact "a linked worktree of a bare repository at <dir>/.git does not run the lefthook in <dir>" nothing_installed
fact "a linked worktree of a bare repository at <dir>/.git does not point at npm ci in a main worktree" not said "npm ci"

prepare "$tmp/outside"
fact "outside a git repository it succeeds" succeeded
fact "outside a git repository it installs nothing" nothing_installed

prepare "$tmp/broken"
fact "a git error other than being outside a repository fails" failed
fact "a git error other than being outside a repository shows git's message" said "bad config line"
fact "a git error other than being outside a repository installs nothing" nothing_installed

mv "$tmp/repo/node_modules" "$tmp/moved"
prepare "$tmp/wt"
fact "without lefthook in the main worktree a linked worktree fails" failed
fact "without lefthook in the main worktree it installs nothing" nothing_installed
fact "without lefthook in the main worktree it says to run npm ci" said "npm ci"
fact "without lefthook in the main worktree it names the main worktree" said "$(cd "$tmp/repo" && pwd -P)"

fact "lefthook run does not reinstall the hooks (no_auto_install in lefthook.yml)" auto_install_off

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
