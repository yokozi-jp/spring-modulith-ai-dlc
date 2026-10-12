#!/usr/bin/env bash
# test.sh: runs session-sql-lint.sh against each fixture tree and checks the exit code and a message fragment.
# Run: bash tooling/session-sql-lint/test.sh (task lint-session-sql runs it before the real check)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
out="$(mktemp)"
trap 'rm -f "$out"' EXIT
failures=0

# expect <fixture> <exit code> <message fragment, empty for none>
expect() {
  local fixture="$1" want="$2" fragment="$3" code
  bash "$dir/session-sql-lint.sh" "$dir/fixtures/$fixture" >"$out" 2>&1
  code=$?
  if [ "$code" != "$want" ] || { [ -n "$fragment" ] && ! grep -qF -- "$fragment" "$out"; }; then
    echo "FAIL: $fixture (exit $code, want $want; fragment '$fragment')"
    sed 's/^/    /' "$out"
    failures=$((failures + 1))
  fi
}

# The ok tree holds transaction-scoped locks, identifiers, and comments that must not be reported.
expect ok 0 "session-sql-lint OK"
for sample in "SELECT pg_try_advisory_lock(1)" "select pg_advisory_unlock_all()" "LISTEN orders" \
  "DECLARE c CURSOR WITH HOLD FOR SELECT 1" "PREPARE q (int) AS SELECT 1" "DEALLOCATE q" \
  "CREATE TEMP TABLE t (id int) ON COMMIT DELETE ROWS" "LOAD 'auto_explain'" "SELECT pg_advisory_lock(42);"; do
  expect detects 1 "$sample"
done

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
