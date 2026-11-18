#!/usr/bin/env bash
# scratch-git-check.sh: 一時の git リポジトリを作る自己検査が、作る前に scratch-git.sh を source していることを確かめる。
# Run: bash tooling/scratch-git/scratch-git-check.sh [<ファイル>...] (task lint-scratch-git が引数なしで実行する)
# 引数がなければ tooling/*/test.sh と .kiro/hooks/*.test.sh を見る。引数は、絶対パスかリポジトリのルートからのパスで渡す。
# コメントを除いた行の git init、git clone、git worktree add を、作る操作とみなす。
# ponytail: 行ごとの正規表現なので、次は見落とす。見落としが問題になったら、bash の構文木を読む検査に替える。
#   変数を通した git（"$GIT" init）、git submodule add、別のファイルの関数やスクリプトが作るリポジトリ、
#   一重引用符の中に空白がある -C の引数、git init と同じ行で前にある引用符の中の「 #」。
#   source の行は、実行されない分岐の中にあっても source したとみなす。文字列やヒアドキュメントの中の git init も数える。

set -u
cd "$(dirname "$0")/../.." || exit 1
[ "$#" -gt 0 ] || set -- tooling/*/test.sh .kiro/hooks/*.test.sh
failures=0

for file in "$@"; do
  line="$(awk '
    BEGIN {
      arg = "(\"[^\"]*\"|[^ \"])+"
      option = "( +(-[Cc]|--git-dir|--work-tree) +" arg "| +-[^ =\"]+(=" arg ")?)"
      create = "git" option "* +(init|clone|worktree +add)([^[:alnum:]_]|$)"
    }
    { sub(/(^|[[:space:]])#.*/, "") }
    /^[[:space:]]*(\.|source)[[:space:]].*\/scratch-git\/scratch-git\.sh/ { sourced = 1 }
    !sourced && $0 ~ create { print FNR; exit }
  ' "$file")"
  if [ -n "$line" ]; then
    echo "FAIL: $file:$line が、tooling/scratch-git/scratch-git.sh を source する前に git のリポジトリを作ります。"
    failures=$((failures + 1))
  fi
done

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
echo "一時の git リポジトリを作る自己検査は、すべて scratch-git.sh を先に source しています。"
