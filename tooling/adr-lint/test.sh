#!/usr/bin/env bash
# test.sh: runs adr-lint.mjs against each fixture tree and checks the exit code and a message fragment.
# Run: bash tooling/adr-lint/test.sh (task adr-lint runs it before the real check)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
out="$(mktemp)"
trap 'rm -f "$out"' EXIT
failures=0

# expect <fixture> <exit code> <message fragment, empty for none>
expect() {
  local fixture="$1" want="$2" fragment="$3" code
  node "$dir/adr-lint.mjs" "$dir/fixtures/$fixture" >"$out" 2>&1
  code=$?
  if [ "$code" != "$want" ] || { [ -n "$fragment" ] && ! grep -q -- "$fragment" "$out"; }; then
    echo "FAIL: $fixture (exit $code, want $want; fragment '$fragment')"
    sed 's/^/    /' "$out"
    failures=$((failures + 1))
  fi
}

expect ok 0 "adr-lint OK"
expect superseded 0 "adr-lint OK"
expect gap 0 "ADR-002 が欠けています"
expect duplicate 1 "ADR-003 の番号が重複しています"
expect missing-index 1 "ADR-003-third.md の索引の行が 0 個です"
expect date-mismatch 1 "日付が索引（2026-02-03）と本文（2026-01-03）で違います"
expect status-mismatch 1 "状態が索引（Accepted）と本文（Proposed）で違います"
expect label-mismatch 1 "ラベルが ADR-003 で、宛先が ADR-001 です"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
