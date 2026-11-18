#!/usr/bin/env bash
# task-calls.sh: lefthook.yml と .github/workflows の YAML が呼ぶ task <名前> が、task --list-all のタスクか別名にあるかを検査する。
# Run: bash tooling/task-calls/task-calls.sh [ルート。省略するとカレント] (task lint-taskfile が実行する)

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
# ponytail: task の後のフラグと VAR=値 を飛ばした語を呼び出しと読む。
#   コメントと name: の行は読まない。
#   次の書き方は別の語を名前と読み、その語が無ければ失敗する。
#   - 英語の文は、task の次の語を名前と読む（The task to run の to）。
#   - 複数行の name: >- は、2 行目から後ろを呼び出しと読む。
#   - 値を取るフラグは、値を名前と読む（task --taskfile x build の x）。
#   - task -- x は、-- の後の x を名前と読む。
#   - 点を含む名前は、点の前までを名前と読む（task a.b の a）。
#   次の書き方は見落とす。
#   見落としは報告しないので、この注記を読まないと気づかない。
#   - 値を取るフラグの後の名前（task -d . build）。
#   - 2 つ目から後ろの名前（task build gone の gone）。
#   - 次の行に続く名前（run: > の折り返し、\ の継続）。
#   - 引用符で囲んだ名前、./bin/task、式や変数で作る名前。
#   - 空白の後の # から後ろ。引用符の中でも同じ（echo "see #1"; task x）。
#   これらを lefthook.yml かワークフローに書くときは、
#   YAML を解析し、run を shell の規則で分ける node のスクリプトに替える。

root="${1:-.}"
err="$(mktemp)"
trap 'rm -f "$err"' EXIT
# task は成功しても、標準エラーに警告を出すことがある（TASK_X_ の実験機能の案内など）。
# 警告はこの検査の出力に混ぜず、task が失敗したときだけ出す。
if ! names="$(task -d "$root" --list-all --silent 2>"$err")"; then
  cat "$err" >&2
  exit 1
fi
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
