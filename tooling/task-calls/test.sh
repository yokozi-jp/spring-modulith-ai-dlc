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
  out="$(LC_ALL=C.UTF-8 bash "$dir/task-calls.sh" "$tmp/$1" 2>"$tmp/err")"
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
        task -x build
        task --exit-code b VAR="a b"
        task VAR=1 build
        task check -- --flag
        echo it's done >&2 # task gone
        echo "my-task gone と .task gone の task は語とパスの一部です。"
        echo "task　gone の全角空白は shell の区切りではありません。"
        task --list-all 2>&1 >/dev/null
        echo "失敗したら task check: で再実行してください。"
      fail_text: "失敗しました。task buildで再実行してください。"
EOF
cat >"$tmp/ok/.github/workflows/ci.yml" <<'EOF'
jobs:
  ci:
    steps:
      - uses: go-task/setup-task@0123 # v2
      - name: Run task gone 📚
        run: PATH="$PWD/bin:$PATH" task check
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
    - run: task g
    - run: task buil
    - run: task secret
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

expect ok 0 'task-calls OK: lefthook.yml と .github/workflows の task の呼び出し 7 件が、すべて task --list-all にあります。' ''

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
lefthook.yml:14: task gone-block $m
lefthook.yml:15: task gone-text $m
.github/workflows/ci.yaml:5: task gone-workflow $m"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
