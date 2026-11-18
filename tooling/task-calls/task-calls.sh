#!/usr/bin/env bash
# task-calls.sh: lefthook.yml と .github/workflows の YAML が呼ぶ task <名前> が、task --list-all のタスクか別名にあるかを検査する。
# 実行: bash tooling/task-calls/task-calls.sh [ルート。省略するとカレント]（task lint-taskfile が呼ぶ）

set -euo pipefail
# [A-Za-z] を ASCII だけに一致させ、「task fe-checkで再実行」の「で」を名前に入れない。
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
# ponytail: 次の書き方は読み違える。今の呼び出しには無く、増えたらフラグの一覧か、YAML と shell の解析を持つ。
#   値を取るフラグ（-d <ディレクトリ>、--taskfile x）と空白を含む値（VAR="a b"）を名前の前に書くと、値を名前と読む。
#   引用符の中の英語の文（echo "the task failed"）は、task の次の語を名前と読んで失敗する。
#   引用符で囲んだ名前（task "build"）、行の継続（task \ の次の行の名前）、式で作る名前（task ${{ ... }}）は検査しない。

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
    awk '
      # name: は job と step の表示名で、英語の文（Upload task results）にも、
      # branch-protection.md が挙げるチェック名（Run task okf-check）にも使うため読まない。
      # 行を消さずに空けて、grep -n の行番号を元のファイルに合わせる。
      /^[[:space:]]*(-[[:space:]]+)?name:/ { print ""; next }
      # 引用符の外で、行頭か空白の直後の # から後を消す。echo "see #1" の # は残す。
      {
        q = ""
        for (i = 1; i <= length($0); i++) {
          c = substr($0, i, 1)
          if (q != "") { if (c == q) q = "" }
          else if (c == "\"" || c == "\047") q = c
          else if (c == "#" && (i == 1 || substr($0, i - 1, 1) ~ /[ \t]/)) { $0 = substr($0, 1, i - 1); break }
        }
        print
      }
    ' "$file" |
      grep -noE "$call" |
      # 一致は名前で終わるので、最後の語を名前として取る。
      sed -E 's/^([0-9]+):.*[[:space:]]([^[:space:]]+)$/\1 \2/'
  )
done

if [ "$status" -eq 0 ]; then
  echo "task-calls OK: lefthook.yml と .github/workflows の task の呼び出し $calls 件が、すべて task --list-all にあります。"
fi
exit "$status"
