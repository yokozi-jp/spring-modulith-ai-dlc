#!/usr/bin/env bash
# test.sh: 小さな Taskfile、lefthook.yml、ワークフローの木を作り、task-calls.sh の終了コードと出力を期待の文と比べる。
# Run: bash tooling/task-calls/test.sh (task lint-taskfile が本検査の前に実行する)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

# expect <木> <終了コード> <標準出力> <標準エラー>
expect() {
  local out err code
  # 全角空白を [[:space:]] と読むロケールで実行し、task-calls.sh の LC_ALL=C を確かめる。
  # TASK_X_REMOTE_TASKFILES=1 では、task は成功しても標準エラーに警告を出す。
  # この変数を渡し、task-calls.sh が task の警告を出力に混ぜないことを確かめる。
  out="$(LC_ALL=C.UTF-8 TASK_X_REMOTE_TASKFILES=1 bash "$dir/task-calls.sh" "$tmp/$1" 2>"$tmp/err")"
  code=$?
  err="$(cat "$tmp/err")"
  if [ "$code" != "$2" ] || [ "$out" != "$3" ] || [ "$err" != "$4" ]; then
    echo "FAIL: $1 (exit $code, want $2)"
    diff <(printf '%s\n' "$3" "$4") <(printf '%s\n' "$out" "$err") | sed 's/^/    /'
    failures=$((failures + 1))
  fi
}

for t in ok bad; do
  mkdir -p "$tmp/$t/.github/workflows"
  cat >"$tmp/$t/Taskfile.yml" <<'EOF'
version: "3"
tasks:
  build:
    aliases: [b]
    cmds: [echo build]
  check:
    cmds: [echo check]
  ns:x:
    cmds: [echo ns:x]
  secret:
    internal: true
    cmds: [echo secret]
EOF
done

# ok の木は、呼び出しと読まない書き方と、ある名前の呼び出しだけを持つ。
cat >"$tmp/ok/lefthook.yml" <<'EOF'
pre-commit:
  jobs:
    # task gone はコメントなので数えない。
    - name: Run task gone
      run: |
        task -x build # 数える
        task --exit-code b VAR="a b" # 数える
        task VAR=1 build # 数える
        task check -- --flag # 数える
        task ns:x # 数える
        echo it's done >&2 # task gone
        echo "my-task gone と .task gone の task は語とパスの一部です。"
        my_task gone-under
        ./bin/task gone-path # 見落とす書き方（task-calls.sh の ponytail: の注記）
        echo "task　gone の全角空白は shell の区切りではありません。"
        task --list-all 2>&1 >/dev/null
        echo "失敗したら task check: で再実行してください。" # 数える
      fail_text: "失敗しました。task buildで再実行してください。" # 数える
EOF
cat >"$tmp/ok/.github/workflows/ci.yml" <<'EOF'
jobs:
  ci:
    steps:
      - uses: go-task/setup-task@0123 # v2
      - name: Run task gone 📚
        run: PATH="$PWD/bin:$PATH" task check # 数える
EOF

# bad の木は、呼び出しと読む書き方ごとに、無い名前を一つずつ呼ぶ。
# 読み落としも余分な報告も、標準エラーを期待の文と食い違わせる。
cat >"$tmp/bad/lefthook.yml" <<'EOF'
pre-commit:
  jobs:
    - run: task -x gone-flag
    - run: task VAR=1 gone-vars
    - run: task gone-redirect 2>&1
    - run: task gone-args -- --flag VAR=1
    - run: task build && task gone-second
    - run: echo a#b; task gone-hash
    - run: task g # 1 文字の名前も読む（別名の b と同じ形）。
    - run: task buil # build の一部だけの名前は一致としない。
    - run: task secret # internal のタスクは CLI から呼べない。
    - run: task ns:gone
    - run: |
        echo "{staged_files}" >/dev/null
        if true; then task gone-block; fi
      fail_text: "失敗しました。task gone-textで再実行してください。"
EOF
cat >"$tmp/bad/.github/workflows/ci.yaml" <<'EOF'
jobs:
  ci:
    steps:
      - name: Run task gone 📚
        run: task gone-workflow
EOF

# ok の木で数える呼び出しは、行末に # 数える を置いた行である。
# task-calls.sh はコメントを消してから読むので、この印は結果を変えない。
calls="$(cat "$tmp/ok/lefthook.yml" "$tmp/ok/.github/workflows/ci.yml" | grep -c ' # 数える$')"
expect ok 0 "task-calls OK: lefthook.yml と .github/workflows の task の呼び出し $calls 件が、すべて task --list-all にあります。" ''

# 期待の行番号は、ヒアドキュメントの 1 行目を 1 として数える。
m='が task --list-all にありません。タスクを改名、削除したときは、この呼び出しも同じ変更で直してください。'
expect bad 1 '' "lefthook.yml:3: task gone-flag $m
lefthook.yml:4: task gone-vars $m
lefthook.yml:5: task gone-redirect $m
lefthook.yml:6: task gone-args $m
lefthook.yml:7: task gone-second $m
lefthook.yml:8: task gone-hash $m
lefthook.yml:9: task g $m
lefthook.yml:10: task buil $m
lefthook.yml:11: task secret $m
lefthook.yml:12: task ns:gone $m
lefthook.yml:15: task gone-block $m
lefthook.yml:16: task gone-text $m
.github/workflows/ci.yaml:5: task gone-workflow $m"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
