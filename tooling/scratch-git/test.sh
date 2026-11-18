#!/usr/bin/env bash
# test.sh: scratch-git.sh を source した git が別のリポジトリと利用者の設定に触れないことと、
# scratch-git-check.sh が source の前の git だけを失敗にすることを確かめる。
# Run: bash tooling/scratch-git/test.sh (task lint-scratch-git が実行する)

set -u
. "$(dirname "$0")/../scratch-git/scratch-git.sh" || exit 1

dir="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
script="$tmp/test.sh"
failures=0
src='. "$(dirname "$0")/../scratch-git/scratch-git.sh" || exit 1'

fail() {
  echo "FAIL: $1"
  sed 's/^/    /' "$tmp/out"
  failures=$((failures + 1))
}

# フックの中のように GIT_DIR などが別のリポジトリを指し、利用者の設定が commit の署名を求めても、
# source した後の git init と commit は一時のディレクトリの中で済む。
git init -q "$tmp/decoy" && git -C "$tmp/decoy" commit -q --allow-empty -m decoy || exit 1
mkdir "$tmp/home" "$tmp/scratch"
printf '[commit]\n\tgpgsign = true\n[gpg]\n\tprogram = false\n' >"$tmp/home/.gitconfig"
snapshot() { (cd "$tmp/decoy/.git" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum); }
before="$(snapshot)"
env -u GIT_CONFIG_GLOBAL HOME="$tmp/home" \
  GIT_DIR="$tmp/decoy/.git" GIT_INDEX_FILE="$tmp/decoy/.git/index" GIT_WORK_TREE="$tmp/decoy" \
  bash -c '. "$1" || exit 1; cd "$2" && git init -q && echo x >f && git add f && git commit -q -m scratch' \
  _ "$dir/scratch-git.sh" "$tmp/scratch" >"$tmp/out" 2>&1 ||
  fail "git init and commit after sourcing ignore the hook's GIT_DIR and the user's commit.gpgsign"
[ "$(snapshot)" = "$before" ] || fail "the repository that the hook's variables point at is unchanged"

# scene <passes|失敗にする行の番号> <場面> <行>...: 行を test.sh に書いて検査し、成功するか、その行を名指しして失敗するかを確かめる。
scene() {
  local want="$1" label="$2" got=passes
  shift 2
  printf '%s\n' "$@" >"$script"
  bash "$dir/scratch-git-check.sh" "$script" >"$tmp/out" 2>&1 || got="$(sed -n "s|^FAIL: $script:\([0-9]*\) .*|\1|p" "$tmp/out")"
  [ "$got" = "$want" ] || fail "$label (want $want, got $got)"
}

scene passes "git after the source line" "$src" 'git init -q "$tmp/repo"'
scene 1 "git before the source line" 'git init -q "$tmp/repo"' "$src"
scene 1 "git status before the source line" 'git status' "$src"
scene 1 "git in a command substitution" 'top="$(git rev-parse --show-toplevel)"' "$src"
scene passes "the source keyword" "source ${src#. }" 'git status'
scene passes "an indented source line" "  $src" 'git status'
scene passes "git in a comment line" '# git status' "$src"
scene passes "git in a trailing comment" 'cd "$tmp" # then git status' "$src"
scene 2 "a commented-out source line" "# $src" 'git status'
bash "$dir/scratch-git-check.sh" "$tmp/missing.sh" >"$tmp/out" 2>&1 && fail "a missing file fails the check"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
