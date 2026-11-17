#!/usr/bin/env bash
# task-calls.sh: lefthook.yml と .github/workflows の YAML が呼ぶ task <名前> が、task --list-all のタスクか別名にあるかを検査する。
# 実行: bash tooling/task-calls/task-calls.sh [ルート。省略するとカレント]（task lint-taskfile が呼ぶ）

set -euo pipefail
# [A-Za-z] を ASCII だけに一致させ、「task fe-checkで再実行」の「で」を名前に入れない。
export LC_ALL=C

root="${1:-.}"
names="$(task -d "$root" --list-all --silent)"
calls=0
status=0

for file in "$root/lefthook.yml" "$root"/.github/workflows/*.yml "$root"/.github/workflows/*.yaml; do
  [ -f "$file" ] || continue
  # コメントを消してから、task の後の -x などのフラグを飛ばした最初の語を名前として取る。
  # 変数（VAR=...）、-- の後の引数、2つ目以降の名前は見ない。
  # ponytail: -d <ディレクトリ> のように値を取るフラグを書くと、値を名前と読んで失敗する。そのときはフラグの一覧を持つ。
  while read -r line name; do
    calls=$((calls + 1))
    if ! grep -qxF -- "$name" <<<"$names"; then
      echo "${file#"$root"/}:$line: task $name が task --list-all にありません。タスクを改名、削除したときは、この呼び出しも同じ変更で直してください。" >&2
      status=1
    fi
  done < <(
    sed -E 's/(^|[[:space:]])#.*$//' "$file" |
      grep -noE '(^|[^A-Za-z0-9_./-])task([[:space:]]+-[^[:space:]]+)*[[:space:]]+[A-Za-z0-9_]([A-Za-z0-9_:-]*[A-Za-z0-9_])?' |
      sed -E 's/^([0-9]+):.*[[:space:]]/\1 /'
  )
done

if [ "$status" -eq 0 ]; then
  echo "task-calls OK: lefthook.yml と .github/workflows の task の呼び出し $calls 件が、すべて task --list-all にあります。"
fi
exit "$status"
