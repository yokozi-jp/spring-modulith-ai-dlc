#!/usr/bin/env bash
# 削除した識別子（names.txt）が、docs、.agents、backend/src に残っていないことを確かめる。
# 使い方: tooling/removed-names/check.sh [names.txt]（リポジトリのどこから実行してもよい）
# 残してよいのは、names.txt に書いたパスだけである（判断の履歴）。
# 規約や skill が旧名のまま残ると、エージェントがそれを写して、削除した API を呼ぶためである。
set -euo pipefail

dir="$(cd "$(dirname "$0")" && pwd)"
names="${1:-$dir/names.txt}"
roots=(docs .agents backend/src)
excluded=(':!backend/src/generated')
status=0

while IFS=$'\t' read -r name allowed; do
  case "$name" in '' | '#'*) continue ;; esac
  # 区切りがタブでない行は、識別子と正規表現が一語になり、何にも一致せず検査が黙って通る。
  if ! [[ "$name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "FAIL names.txt の行が「<識別子><TAB><正規表現か ->」の形でない: $name" >&2
    status=1
    continue
  fi
  allowed="${allowed:--}"
  hits="$(git grep -nIw -e "$name" -- "${roots[@]}" "${excluded[@]}" || true)"
  [ -z "$hits" ] && continue
  if [ "$allowed" != "-" ]; then
    hits="$(printf '%s\n' "$hits" | grep -Ev "^($allowed)" || true)"
    [ -z "$hits" ] && continue
  fi
  echo "FAIL 削除した識別子 $name が残っている。新しい名前に直す（履歴として残してよいパスは names.txt に書く）" >&2
  printf '%s\n' "$hits" | sed 's/^/    /' >&2
  status=1
done <"$names"

if [ "$status" -eq 0 ]; then
  echo "OK   removed-names: 削除した識別子は残っていない"
fi
exit "$status"
