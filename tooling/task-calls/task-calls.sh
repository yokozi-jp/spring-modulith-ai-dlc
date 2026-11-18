#!/usr/bin/env bash
# task-calls.sh: lefthook.yml と .github/workflows の YAML が呼ぶ task <名前> が、task --list-all のタスクか別名にあるかを検査する。
# 実行: bash tooling/task-calls/task-calls.sh [ルート。省略するとカレント]（task lint-taskfile が呼ぶ）

set -euo pipefail
# [[:space:]] を ASCII の空白だけに一致させる。
# UTF-8 のロケールでは、shell が区切らない全角空白（task　gone）も空白と読む。
export LC_ALL=C

# 直前が英数字か _ . / - の task は、setup-task、lint-taskfile、.task のように語やパスの一部である。
boundary='(^|[^A-Za-z0-9_./-])'
# -x、--exit-code などのフラグと VAR=値 は名前ではない。
# grep -E は最長の一致を取るので、task VAR=1 build の名前は VAR ではなく build になる。
skip='([[:space:]]+(-[^[:space:]]+|[A-Za-z_][A-Za-z0-9_]*=[^[:space:]]*))*'
# 英字か _ で始め、task --list-all 2>&1 の 2 を名前と読まない。
# 末尾の - と : は文の区切りとして名前に入れない。
name='[A-Za-z_]([A-Za-z0-9_:-]*[A-Za-z0-9_])?'
call="${boundary}task${skip}[[:space:]]+${name}"
# ponytail: task の後のフラグと VAR=値 を飛ばした語を、コメントと name: 以外のどこでも呼び出しと読む。
#   英語の文は、task の次の語を名前と読む（description: The task to run の to）。
#   複数行の name: >- は、2 行目から後ろを呼び出しと読む。
#   値を取るフラグは、値を名前と読む（task --taskfile x build の x）か、呼び出しを見落とす（task -d . build）。
#   次の行に続く名前（run: > の折り返し、\ の継続）、引用符で囲んだ名前、./bin/task、式や変数で作る名前は見落とす。
#   引用符の中でも空白の後の # はコメントと読むので、echo "see #1"; task x の x を見落とす。
#   これらを lefthook.yml かワークフローに書くときは、YAML を解析して run の文字列を shell の規則で分ける node のスクリプトに替える。

root="${1:-.}"
names="$(task -d "$root" --list-all --silent)"
calls=0
status=0

for file in "$root/lefthook.yml" "$root"/.github/workflows/*.yml "$root"/.github/workflows/*.yaml; do
  [ -f "$file" ] || continue
  while read -r line found; do
    calls=$((calls + 1))
    if ! grep -qxF -- "$found" <<<"$names"; then
      echo "${file#"$root"/}:$line: task $found が task --list-all にありません。タスクを改名、削除したときは、この呼び出しも同じ変更で直してください。" >&2
      status=1
    fi
  done < <(
    # name: は job と step の表示名なので読まない。
    # 表示名には英語の文（Upload task results）と、branch-protection.md が挙げるチェック名（Run task okf-check）を書く。
    # 行頭か空白の直後の # から後ろは、コメントとして消す。
    # 行を消さずに空けて、grep -n の行番号を元のファイルに合わせる。
    sed -E '/^[[:space:]]*(-[[:space:]]+)?name:/s/.*//; s/(^|[[:space:]])#.*$//' "$file" |
      grep -noE "$call" |
      # 一致は名前で終わるので、最後の語を名前として取る。
      sed -E 's/^([0-9]+):.*[[:space:]]([^[:space:]]+)$/\1 \2/'
  )
done

if [ "$status" -eq 0 ]; then
  echo "task-calls OK: lefthook.yml と .github/workflows の task の呼び出し $calls 件が、すべて task --list-all にあります。"
fi
exit "$status"
