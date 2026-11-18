#!/usr/bin/env bash
# scratch-git-check.sh: 自己検査が、scratch-git.sh を source して失敗で止まる行（. …/scratch-git/scratch-git.sh || exit 1）より前で git を実行しないことを確かめる。
# Run: bash tooling/scratch-git/scratch-git-check.sh [<ファイル>...] (task lint-scratch-git が引数なしで実行する)
# 引数は、絶対パスかリポジトリのルートからのパスで渡す。
# ponytail: 行ごとの正規表現なので、次は見落とす。見落としが問題になったら、bash の構文木を読む検査に替える。
#   変数を通した git（"$GIT" status）、source の前に呼ぶほかのスクリプトや task の中の git、
#   git より前にある引用符の中の「 #」（そこから行末までをコメントとみなす）。
#   source の形の行は、実行されない分岐、ヒアドキュメント、\ で続く行の中にあっても source したとみなす。

set -u
cd "$(dirname "$0")/../.." || exit 1
[ "$#" -gt 0 ] || set -- tooling/*/test.sh .kiro/hooks/*.test.sh
failures=0

for file in "$@"; do
  if ! line="$(awk '
    { sub(/(^|[[:space:]])#.*/, "") }
    /^[[:space:]]*(\.|source)[[:space:]].*\/scratch-git\/scratch-git\.sh"?[[:space:]]+\|\|[[:space:]]+exit 1[[:space:]]*$/ { exit }
    /(^|[^[:alnum:]_.-])git([[:space:]]|$)/ { print FNR; exit }
  ' "$file")"; then
    echo "FAIL: $file を読めません。"
    failures=$((failures + 1))
  elif [ -n "$line" ]; then
    echo "FAIL: $file:$line が、tooling/scratch-git/scratch-git.sh を source して || exit 1 で止める行より前に git を実行します。"
    failures=$((failures + 1))
  fi
done

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
echo "自己検査は、すべて scratch-git.sh を source してから git を実行します。"
