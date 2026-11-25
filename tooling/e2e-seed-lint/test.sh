#!/usr/bin/env bash
# test.sh: runs e2e-seed-lint.sh against each fixture tree and checks the exit code and a message fragment.
# Run: bash tooling/e2e-seed-lint/test.sh (task lint-e2e-seed runs it before the real check)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
out="$(mktemp)"
trap 'rm -f "$out"' EXIT
failures=0

# expect <fixture> <exit code> <message fragment>
expect() {
  local fixture="$1" want="$2" fragment="$3" code
  bash "$dir/e2e-seed-lint.sh" "$dir/fixtures/$fixture" >"$out" 2>&1
  code=$?
  if [ "$code" != "$want" ] || ! grep -qF -- "$fragment" "$out"; then
    echo "FAIL: $fixture (exit $code, want $want; fragment '$fragment')"
    sed 's/^/    /' "$out"
    failures=$((failures + 1))
  fi
}

expect ok 0 "e2e-seed-lint OK"
expect detects 1 'bad.spec.ts:3:'
expect detects 1 'orders.ts:2:'
expect detects 1 "productFactory"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
