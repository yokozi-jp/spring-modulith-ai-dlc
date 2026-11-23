#!/usr/bin/env bash
# test.sh: runs db-clock-lint.sh against each fixture tree and checks the exit code and a message fragment.
# Run: bash tooling/db-clock-lint/test.sh (task lint-db-clock runs it before the real check)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
out="$(mktemp)"
trap 'rm -f "$out"' EXIT
failures=0

# expect <fixture> <exit code> <message fragment, empty for none>
expect() {
  local fixture="$1" want="$2" fragment="$3" code
  bash "$dir/db-clock-lint.sh" "$dir/fixtures/$fixture" >"$out" 2>&1
  code=$?
  if [ "$code" != "$want" ] || { [ -n "$fragment" ] && ! grep -qF -- "$fragment" "$out"; }; then
    echo "FAIL: $fixture (exit $code, want $want; fragment '$fragment')"
    sed 's/^/    /' "$out"
    failures=$((failures + 1))
  fi
}

# The ok tree holds a tenant setting, identifiers that contain a keyword, and comments that must not be reported.
expect ok 0 "db-clock-lint OK"
for sample in "SELECT CURRENT_DATE" "select current_timestamp" "SELECT now()" "SELECT sysdate()" \
  "SELECT date_trunc('day', ordered_at)" "SELECT ordered_at AT TIME ZONE 'Asia/Tokyo'" \
  "SELECT ordered_at::date" "SELECT clock_timestamp()" "SELECT localtimestamp"; do
  expect detects 1 "$sample"
done

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
