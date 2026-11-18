#!/usr/bin/env bash
# test.sh: scratch-git-check.sh を小さな test.sh の写しで動かし、source の前に一時の git リポジトリを作る書き方だけを失敗にすることを確かめる。
# Run: bash tooling/scratch-git/test.sh (task lint-scratch-git が実行する)

set -u
# 下の場面の git init の行も、この検査が数える。
. "$(dirname "$0")/../scratch-git/scratch-git.sh"

check="$(cd "$(dirname "$0")" && pwd)/scratch-git-check.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
script="$tmp/test.sh"
failures=0
src='. "$(dirname "$0")/../scratch-git/scratch-git.sh"'

# scene <passes|失敗にする行の番号> <場面> <行>...: 行を test.sh に書いて検査し、成功するか、その行を名指しして失敗するかを確かめる。
scene() {
  local want="$1" label="$2" got=passes
  shift 2
  printf '%s\n' "$@" >"$script"
  bash "$check" "$script" >"$tmp/out" 2>&1 || got="$(sed -n "s|^FAIL: $script:\([0-9]*\) .*|\1|p" "$tmp/out")"
  if [ "$got" != "$want" ]; then
    echo "FAIL: $label (want $want, got $got)"
    sed 's/^/    /' "$tmp/out"
    failures=$((failures + 1))
  fi
}

scene passes "git init after sourcing the helper" "$src" 'git init -q "$tmp/repo"'
scene 1 "git init before sourcing the helper" 'git init -q "$tmp/repo"' "$src"
scene 1 "git clone before sourcing the helper" 'git clone -q "$tmp/a" "$tmp/b"' "$src"
scene 1 "git worktree add with -C before sourcing the helper" 'git -C "$tmp/repo" worktree add -q "$tmp/wt"' "$src"
scene 1 "git -C with a quoted path that has a space" 'git -C "$tmp/a b" init' "$src"
scene 1 "git -c before the subcommand" 'git -c user.name=test init -q "$tmp/repo"' "$src"
scene 1 "git --git-dir= before the subcommand" 'git --git-dir="$tmp/x.git" init' "$src"
scene 1 "git --git-dir with a separate argument" 'git --git-dir "$tmp/x.git" init' "$src"
scene 1 "git --work-tree with a separate argument" 'git --work-tree "$tmp/w" init -q "$tmp/x"' "$src"
scene 1 "git --bare before the subcommand" 'git --bare init -q "$tmp/x.git"' "$src"
scene 1 "git init right before a closing parenthesis" 'out="$(git init)"' "$src"
scene passes "an indented source of the helper" "  $src" 'git init -q "$tmp/repo"'
scene passes "the source keyword" "source ${src#. }" 'git init -q "$tmp/repo"'
scene passes "git init in a comment line" '# git init -q "$tmp/repo"' "$src"
scene passes "git init in a trailing comment" 'cd "$tmp" # then git init' "$src"
scene 2 "a commented-out source does not count" "# $src" 'git init -q "$tmp/repo"'
scene passes "git status and git commit do not create a repository" 'git status' 'git commit -q -m init' "$src"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
