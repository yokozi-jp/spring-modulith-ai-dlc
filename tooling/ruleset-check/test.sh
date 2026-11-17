#!/usr/bin/env bash
# test.sh: runs ruleset-check.mjs against each fixture tree and checks the exit code and a message fragment.
# Run: bash tooling/ruleset-check/test.sh (task ruleset-check runs it before the real check)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
out="$(mktemp)"
trap 'rm -f "$out"' EXIT
failures=0

# expect <fixture> <exit code> <message fragment>
expect() {
  local fixture="$1" want="$2" fragment="$3" code
  node "$dir/ruleset-check.mjs" "$dir/fixtures/$fixture" >"$out" 2>&1
  code=$?
  if [ "$code" != "$want" ] || ! grep -q -- "$fragment" "$out"; then
    echo "FAIL: $fixture (exit $code, want $want; fragment '$fragment')"
    sed 's/^/    /' "$out"
    failures=$((failures + 1))
  fi
}

expect ok 0 "ruleset-check OK"
expect drift 1 "ruleset にあり、docs の一覧にありません: B"
expect drift 1 "docs の一覧にあり、ruleset にありません: C"
expect noworkflow 1 "workflow に同名の name がなく、報告されません: B"
expect nosection 1 "節を読めません"
expect badbullet 1 "一覧の行は"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
