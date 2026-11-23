#!/usr/bin/env bash
# test.sh: be-openapi-check が、書き出しなし、path の誤り、古い spec の 3 つの状態で失敗し、be-test が書き出しの前に契約を消すことを確かめる（#467）。
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
cp "$root/Taskfile.yml" .
mkdir openapi
echo "openapi: 3.1.0" > openapi/openapi.yaml
# api-drift-check は internal なので、include した Taskfile の外から PATHS を渡すために包む。
cat > wrap.yml <<'EOF'
version: "3"
includes:
  real: Taskfile.yml
tasks:
  drift:
    cmds:
      - task: real:api-drift-check
        vars: { PATHS: openapi/missing.yaml }
EOF
git add -A && git commit -q -m base

# expect <名前> <pass|fail> <task の引数>...
expect() {
  local name="$1" want="$2" out status=0
  shift 2
  out="$(task "$@" 2>&1)" || status=$?
  if { [ "$want" = pass ] && [ "$status" -eq 0 ]; } || { [ "$want" = fail ] && [ "$status" -ne 0 ]; }; then
    echo "ok: $name"
  else
    echo "FAIL: $name (期待 $want、終了コード $status)"
    printf '%s\n' "$out" | sed 's/^/    /'
    failures=$((failures + 1))
  fi
}

expect "書き出した契約がコミット済みと同じなら通る" pass be-openapi-check
echo "openapi: 3.1.1" > openapi/openapi.yaml
expect "古い契約はコミット済みと食い違い失敗する" fail be-openapi-check
git checkout -q -- openapi/openapi.yaml
rm openapi/openapi.yaml
expect "テストが契約を書き出さなければ失敗する" fail be-openapi-check
git checkout -q -- openapi/openapi.yaml
expect "追跡されていない path は失敗する" fail -t wrap.yml drift

# be-test の本体（gradle）は動かさず、--dry の出力で、契約の削除が gradle より前にあることを確かめる。
dry="$(task be-test --dry 2>&1)"
rm_line="$(printf '%s\n' "$dry" | grep -n 'rm -f.*openapi/openapi.yaml' | head -1 | cut -d: -f1)"
gradle_line="$(printf '%s\n' "$dry" | grep -n 'gradlew test' | head -1 | cut -d: -f1)"
if [ -n "$rm_line" ] && [ -n "$gradle_line" ] && [ "$rm_line" -lt "$gradle_line" ]; then
  echo "ok: be-test は gradle の前に契約を消す"
else
  echo "FAIL: be-test が gradle の前に openapi/openapi.yaml を消していない"
  printf '%s\n' "$dry" | sed 's/^/    /'
  failures=$((failures + 1))
fi

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
