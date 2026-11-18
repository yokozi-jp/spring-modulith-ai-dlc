#!/usr/bin/env bash
# test.sh: 小さな Taskfile、lefthook.yml、ワークフローの木を作って task-calls.sh の終了コードとメッセージを確かめる。
# Run: bash tooling/task-calls/test.sh (task lint-taskfile が本検査の前に実行する)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
out="$(mktemp)"
trap 'rm -rf "$tmp" "$out"' EXIT
failures=0

# tree <名前> <lefthook.yml の jobs の行> <ci.yml の steps の行>
# Taskfile は build（別名 b）、check、内部タスク secret を持つ。
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
  printf 'pre-commit:\n  jobs:\n%s\n' "$2" >"$t/lefthook.yml"
  printf 'jobs:\n  ci:\n    steps:\n%s\n' "$3" >"$t/.github/workflows/ci.yml"
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

# missing <名前> <ファイル> <呼び出しの行の断片> <タスク名>
# 期待する行番号は、木のファイルで断片を含む最初の行から取る。
missing() {
  local line
  line="$(grep -nF -m1 -- "$3" "$tmp/$1/$2" | cut -d: -f1)"
  expect "$1" 1 "$2:$line: task $4 が task --list-all にありません。"
}

ok_hook='    # task gone はコメントなので数えない。
    - name: Run task gone
      run: |
        task -x build
        task --exit-code b VAR="a b"
        task VAR=1 build
        task check -- --flag
        echo "docker が無いため task check をスキップします。" >&2 # task gone
        echo "my-task gone と .task gone の task は語とパスの一部です。"
        task --list-all >/dev/null
        task --list-all 2>&1
        task --list-all 2>/dev/null
        task --list-all 1>&2
      fail_text: "失敗しました。task buildで再実行してください。"'
ok_workflow='      - uses: go-task/setup-task@0123 # v2
      - name: Run task gone 📚
        run: PATH="$PWD/bin:$PATH" task check'

tree ok "$ok_hook" "$ok_workflow"
expect ok 0 "すべて task --list-all にあります。"

tree flag '    - run: task -x gone' "$ok_workflow"
missing flag lefthook.yml 'task -x gone' gone

tree vars-after '    - run: task gone VAR=1' "$ok_workflow"
missing vars-after lefthook.yml 'task gone' gone

tree vars-before '    - run: task VAR=1 gone' "$ok_workflow"
missing vars-before lefthook.yml 'task VAR=1 gone' gone

tree args '    - run: task gone -- --flag' "$ok_workflow"
missing args lefthook.yml 'task gone' gone

tree multiline '    - run: |
        echo "{staged_files}" >/dev/null
        if true; then task gone; fi' "$ok_workflow"
missing multiline lefthook.yml 'task gone' gone

tree prefix '    - run: task buil' "$ok_workflow"
missing prefix lefthook.yml 'task buil' buil

tree internal '    - run: task secret' "$ok_workflow"
missing internal lefthook.yml 'task secret' secret

tree echo '    - run: echo "task goneで再実行してください。"' "$ok_workflow"
missing echo lefthook.yml 'task gone' gone

tree fail-text '    - run: task build
      fail_text: "失敗しました。task goneで再実行してください。"' "$ok_workflow"
missing fail-text lefthook.yml 'task gone' gone

tree hash-in-quotes '    - run: echo "see #1"; task gone' "$ok_workflow"
missing hash-in-quotes lefthook.yml 'task gone' gone

tree workflow "$ok_hook" '      - name: Run task gone 📚
        run: task gone'
missing workflow .github/workflows/ci.yml 'run: task gone' gone

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
