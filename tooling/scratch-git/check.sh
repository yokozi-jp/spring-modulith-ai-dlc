#!/usr/bin/env bash
# check.sh: 一時の git リポジトリを作る自己検査が、作る前に scratch-git.sh を source していることを確かめる。
# Run: bash tooling/scratch-git/check.sh (task lint-scratch-git が実行する)
# 対象は tooling/*/test.sh と .kiro/hooks/*.test.sh で、コメントでない行の git init、git clone、git worktree add を作る操作とみなす。

set -u
cd "$(dirname "$0")/../.." || exit 1
failures=0

for file in tooling/*/test.sh .kiro/hooks/*.test.sh; do
  line="$(awk '
    /^[[:space:]]*#/ { next }
    /^\. .*\/scratch-git\/scratch-git\.sh"$/ { sourced = 1 }
    /git( +-[Cc] +[^ ]+)* +(init|clone|worktree +add)( |$)/ && !sourced { print FNR; exit }
  ' "$file")"
  if [ -n "$line" ]; then
    echo "FAIL: $file:$line が、tooling/scratch-git/scratch-git.sh を source する前に git のリポジトリを作ります。"
    failures=$((failures + 1))
  fi
done

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
echo "一時の git リポジトリを作る自己検査は、すべて scratch-git.sh を先に source しています。"
