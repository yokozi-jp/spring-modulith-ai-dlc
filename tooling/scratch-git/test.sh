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

# linked worktree のフックのように GIT_DIR と GIT_INDEX_FILE が別のリポジトリの worktree を指し、利用者とシステムの設定が commit の署名を求めても、
# source した後の git init と commit は一時のディレクトリの中で済む。切り離さないと、git init が別のリポジトリを core.bare=true にする。
# 内側の bash は env -i で始め、この test.sh が source した値を引き継がない。
git init -q "$tmp/decoy" && git -C "$tmp/decoy" commit -q --allow-empty -m decoy &&
  git -C "$tmp/decoy" worktree add -q "$tmp/wt" ||
  { echo "FAIL: setting up the decoy repository and its worktree"; exit 1; }
mkdir "$tmp/home" "$tmp/scratch"
printf '[commit]\n\tgpgsign = true\n[gpg]\n\tprogram = false\n' | tee "$tmp/home/.gitconfig" >"$tmp/system.gitconfig"
snapshot() { (cd "$tmp/decoy/.git" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum); }
before="$(snapshot)"
gitdir="$tmp/decoy/.git/worktrees/wt"
env -i PATH="$PATH" HOME="$tmp/home" GIT_CONFIG_SYSTEM="$tmp/system.gitconfig" \
  GIT_DIR="$gitdir" GIT_INDEX_FILE="$gitdir/index" \
  bash -c '. "$1" || exit 1; cd "$2" && git init -q && echo x >f && git add f && git commit -q -m scratch' \
  _ "$dir/scratch-git.sh" "$tmp/scratch" >"$tmp/out" 2>&1 ||
  fail "git init and commit after sourcing ignore the hook's GIT_DIR and the commit.gpgsign of the user and the system"
[ "$(snapshot)" = "$before" ] || fail "the repository that the hook's variables point at is unchanged"

# TMPDIR がリポジトリの中にあっても、source した後の git は、mktemp で作ったディレクトリから上のリポジトリを見つけない。
mkdir "$tmp/decoy/tmp"
env -i PATH="$PATH" HOME="$tmp/home" TMPDIR="$tmp/decoy/tmp" \
  bash -c '. "$1" || exit 1; cd "$(mktemp -d)" && ! git rev-parse --git-dir' _ "$dir/scratch-git.sh" >"$tmp/out" 2>&1 ||
  fail "git after sourcing finds no repository above a mktemp directory inside a repository"

# scene <passes|失敗にする行の番号> <ケース> <行>...: 行を test.sh に書いて検査し、成功するか、その行を名指しして失敗するかを確かめる。
scene() {
  local want="$1" label="$2" got=passes
  shift 2
  printf '%s\n' "$@" >"$script"
  bash "$dir/scratch-git-check.sh" "$script" >"$tmp/out" 2>&1 || got="$(sed -n "s|^FAIL: $script:\([0-9]*\) .*|\1|p" "$tmp/out")"
  [ "$got" = "$want" ] || fail "$label (want $want, got $got)"
}

scene passes "git after the source line" "$src" 'git init -q "$tmp/repo"'
scene 1 "git status before the source line" 'git status' "$src"
scene 1 "git by its path before the source line" '/usr/bin/git status' "$src"
scene 1 "git in a command substitution" 'top="$(git rev-parse --show-toplevel)"' "$src"
scene passes "git inside a word or a path" 'cd .git && :' 'task lint-scratch-git' 'echo github' 'echo legit use_git' \
  'cat "$tmp/git.log" docs/git-hooks.md "$git_dir"' "$src"
scene 1 "git before a ;" 'git; :' "$src"
scene 1 "git before a quote" '"git" status' "$src"
scene passes "the source keyword" "source ${src#. }" 'git status'
scene passes "an indented source line" "  $src" 'git status'
scene 2 "a source line without || exit 1" "${src% || exit 1}" 'git status'
scene passes "git in a comment line" '# git status' "$src"
scene passes "git in a trailing comment" 'cd "$tmp" # then git status' "$src"
scene 1 "git after a # inside a word" 'n=${#x}; git status' "$src"
scene 2 "a source line as an argument" "echo $src" 'git status'
scene 2 "a source line run in the background" "$src &" 'git status'
bash "$dir/scratch-git-check.sh" "$tmp/missing.sh" >"$tmp/out" 2>&1 && fail "a missing file fails the check"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
