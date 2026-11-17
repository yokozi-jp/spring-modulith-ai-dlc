#!/usr/bin/env bash
# test.sh: 小さな Taskfile、lefthook.yml、ワークフローの木を作って task-calls.sh の終了コードとメッセージを確かめる。
# Run: bash tooling/task-calls/test.sh (task lint-taskfile が本検査の前に実行する)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
out="$(mktemp)"
trap 'rm -rf "$tmp" "$out"' EXIT
failures=0

# tree <名前> <lefthook.yml の run の行> <ワークフローの run の行>
# Taskfile は build（別名 b）、check、内部タスク secret を持つ。run の行は lefthook.yml の 5 行目以降、ワークフローの 6 行目に入る。
tree() {
  local t="$tmp/$1"
  mkdir -p "$t/.github/workflows"
  cat >"$t/Taskfile.yml" <<'EOF'
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
  printf 'pre-commit:\n  jobs:\n    - name: job\n      run: |\n%s\n      fail_text: "失敗しました。task buildで再実行してください。"\n' "$2" >"$t/lefthook.yml"
  printf 'jobs:\n  ci:\n    steps:\n      - uses: go-task/setup-task@0123 # v2\n      - name: Run task check\n        run: %s\n' "$3" >"$t/.github/workflows/ci.yml"
}

# expect <名前> <終了コード> <メッセージの断片>
expect() {
  local code
  bash "$dir/task-calls.sh" "$tmp/$1" >"$out" 2>&1
  code=$?
  if [ "$code" != "$2" ] || ! grep -qF -- "$3" "$out"; then
    echo "FAIL: $1 (exit $code, want $2; fragment '$3')"
    sed 's/^/    /' "$out"
    failures=$((failures + 1))
  fi
}

ok_hook='        # task gone はコメントなので数えない。
        task -x build
        task --exit-code b VAR="a b"
        task check -- --flag
        echo "docker が無いため task check をスキップします。" >&2 # task gone
        task --list-all >/dev/null'
ok_workflow='PATH="$PWD/bin:$PATH" task check'

tree ok "$ok_hook" "$ok_workflow"
expect ok 0 "task の呼び出し 7 件が、すべて task --list-all にあります。"

tree flag '        task -x gone' "$ok_workflow"
expect flag 1 "lefthook.yml:5: task gone が task --list-all にありません。"

tree vars '        task gone VAR=1' "$ok_workflow"
expect vars 1 "lefthook.yml:5: task gone が"

tree args '        task gone -- --flag' "$ok_workflow"
expect args 1 "lefthook.yml:5: task gone が"

tree multiline '        echo "{staged_files}" >/dev/null
        if true; then task gone; fi' "$ok_workflow"
expect multiline 1 "lefthook.yml:6: task gone が"

tree prefix '        task buil' "$ok_workflow"
expect prefix 1 "lefthook.yml:5: task buil が"

tree internal '        task secret' "$ok_workflow"
expect internal 1 "lefthook.yml:5: task secret が"

tree message '        echo "task goneで再実行してください。"' "$ok_workflow"
expect message 1 "lefthook.yml:5: task gone が"

tree workflow '        task build' 'task gone'
expect workflow 1 ".github/workflows/ci.yml:6: task gone が"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
