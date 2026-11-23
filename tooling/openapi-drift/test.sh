#!/usr/bin/env bash
# test.sh: be-openapi-check が、テストの書き出しなし、古い契約、追跡されていない path で失敗することを確かめる（#467）。
# gradlew は、契約を書くか書かないかだけを変える偽物に置き換える。
# Run: bash tooling/openapi-drift/test.sh (task lint-taskfile が実行する)

set -u
. "$(dirname "$0")/../scratch-git/scratch-git.sh" || exit 1

root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

# api-lint が呼ぶ docker は true に差し替え、drift の検査だけを動かす。
export DOCKER=true

git init -q "$tmp/repo" -b main
cd "$tmp/repo" || exit 1
cp "$root/Taskfile.yml" "$root/.env.test.example" .
mkdir openapi backend
echo "openapi: 3.1.0" > openapi/openapi.yaml
printf '#!/bin/sh\n[ -z "$FAKE_WRITE" ] || echo "$FAKE_WRITE" > ../openapi/openapi.yaml\n' > backend/gradlew
chmod +x backend/gradlew
# api-drift-check は internal なので、include した Taskfile の外から PATHS を渡すために包む。
cat > wrap.yml <<'WRAP'
version: "3"
includes:
  real: Taskfile.yml
tasks:
  drift:
    cmds:
      - task: real:api-drift-check
        vars: { PATHS: openapi/missing.yaml }
WRAP
printf 'build/\n.env.test\n' > .gitignore
git add -A && git commit -q -m base

# expect <名前> <pass|fail> <出力に含まれる文字列。無ければ空> <コマンド>...
expect() {
  local name="$1" want="$2" needle="$3" out status=0
  shift 3
  out="$("$@" 2>&1)" || status=$?
  if { [ "$want" = pass ] && [ "$status" -eq 0 ]; } ||
    { [ "$want" = fail ] && [ "$status" -ne 0 ] && [[ "$out" == *"$needle"* ]]; }; then
    echo "ok: $name"
  else
    echo "FAIL: $name (期待 $want「$needle」、終了コード $status)"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  fi
}

# be-test の後に be-openapi-check を実行する。FAKE_WRITE が空ならテストは契約を書かない。
after_be_test() {
  FAKE_WRITE="$1" task be-test >/dev/null 2>&1 || { echo "be-test が失敗しました"; return 1; }
  task be-openapi-check
}

expect "be-test なしでは失敗する" fail "書き出されていません" task be-openapi-check
expect "テストが同じ契約を書けば通る" pass "" after_be_test "openapi: 3.1.0"
git checkout -q -- openapi/openapi.yaml
expect "テストが契約を書かなければ失敗する" fail "書き出されていません" after_be_test ""
expect "テストが古い契約を書けばコミット済みと食い違い失敗する" fail "一致しません" after_be_test "openapi: 3.1.1"
git checkout -q -- openapi/openapi.yaml
expect "追跡されていない path は失敗する" fail "追跡されていない" task -t wrap.yml drift

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
