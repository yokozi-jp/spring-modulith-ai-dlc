#!/usr/bin/env bash
# test.sh: 一時の git リポジトリと linked worktree で prepare.mjs を動かし、hook を入れる lefthook と作業ツリーを確かめる。
# Run: bash tooling/lefthook-install/test.sh (task lint-lefthook が実行する)

set -u
# フックの中で実行されると GIT_DIR などが入っている。消さないと、一時のリポジトリの git が本物のリポジトリを書き換える。
unset $(git rev-parse --local-env-vars)

dir="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$dir/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

# stub <置き場所> <名前>: 呼ばれた作業ツリーと名前を記録する lefthook の代わりを置く。
stub() {
  mkdir -p "$1"
  printf '#!/bin/sh\necho "%s $(pwd)" >>"%s/installed"\n' "$2" "$tmp" >"$1/lefthook"
  chmod +x "$1/lefthook"
}

git init -q "$tmp/repo"
git -C "$tmp/repo" -c user.name=t -c user.email=t@example.com commit -q --allow-empty -m init
git -C "$tmp/repo" worktree add -q "$tmp/wt" 2>/dev/null
main="$(cd "$tmp/repo" && pwd)"
stub "$tmp/repo/node_modules/.bin" main
stub "$tmp/wt/node_modules/.bin" worktree

# npm は作業ツリーの node_modules/.bin を PATH の先頭に置いて prepare を実行する。
prepare() { (cd "$1" && PATH="$1/node_modules/.bin:$PATH" node "$root/tooling/lefthook-install/prepare.mjs" >/dev/null); }

fact() {
  local label="$1"
  shift
  if ! "$@"; then
    echo "FAIL: $label"
    failures=$((failures + 1))
  fi
}

prepare "$tmp/wt"
fact "the linked worktree installs the hooks with the main worktree's lefthook" [ "$(cat "$tmp/installed" 2>/dev/null)" = "main $main" ]
: >"$tmp/installed"
prepare "$tmp/repo"
fact "the main worktree installs the hooks with its own lefthook" [ "$(cat "$tmp/installed")" = "main $main" ]
: >"$tmp/installed"
mv "$tmp/repo/node_modules" "$tmp/moved"
prepare "$tmp/wt"
fact "without lefthook in the main worktree it installs nothing" [ ! -s "$tmp/installed" ]
mkdir "$tmp/outside"
prepare "$tmp/outside"
fact "outside a git repository it installs nothing" [ ! -s "$tmp/installed" ]
fact "lefthook does not sync the hooks when it runs" grep -qx 'no_auto_install: true' "$root/lefthook.yml"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
