#!/usr/bin/env bash
# test.sh: runs check.sh and mutants.py on the counter fixture and checks the exit codes and output lines.
# Run: bash tooling/formal-verify/test.sh (task formal-verify-test runs it). TLC and Java come as check.sh fetches them.

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
fixture="$dir/fixtures/counter"
runs="${XDG_CACHE_HOME:-$HOME/.cache}/formal-verify-tla/runs"
tmp="$(mktemp -d)"
out="$tmp/out"
trap 'rm -rf "$tmp"' EXIT
failures=0

# expect <label> <exit code> <output line fragment>... -- <command>...
expect() {
  local label="$1" want="$2" code fragment
  shift 2
  local fragments=()
  while [ "$1" != "--" ]; do fragments+=("$1"); shift; done
  shift
  "$@" >"$out" 2>&1
  code=$?
  for fragment in "${fragments[@]}"; do
    if [ "$code" != "$want" ] || ! grep -qF -- "$fragment" "$out"; then
      echo "FAIL: $label (exit $code, want $want; fragment '$fragment')"
      sed 's/^/    /' "$out"
      failures=$((failures + 1))
      return
    fi
  done
}

kept_runs() { find "$runs" -maxdepth 1 -name "$1.*" 2>/dev/null | wc -l; }

before="$(kept_runs holds)"
expect "check.sh passes when every cfg gives its expected result" 0 \
  "OK   breaks: BelowThree (expected)" "OK   holds: pass (expected)" -- \
  "$dir/check.sh" "$fixture"
[ "$(kept_runs holds)" = "$before" ] || { echo "FAIL: check.sh kept the TLC output of a run that gave its expected result"; failures=$((failures + 1)); }

cp -r "$fixture" "$tmp/counter"
sed -i '1s/.*/\\* expect: pass/' "$tmp/counter/model/breaks.cfg"
expect "check.sh fails when TLC reports a violation the cfg does not expect" 1 \
  "FAIL breaks: got BelowThree, expected pass. TLC output: " -- \
  "$dir/check.sh" "$tmp/counter" breaks
kept="$(grep -oP 'TLC output: \K\S+' "$out")"
if [ -f "$kept" ]; then rm -rf "$(dirname "$kept")"; else echo "FAIL: check.sh did not keep the TLC output of a failed run"; failures=$((failures + 1)); fi

expect "mutants.py catches a real mutant and rejects a vacuous one" 1 \
  "control  off-by-one: the unmutated model passes holds with AtMostLimit" \
  "caught   off-by-one -> AtMostLimit on holds: the guard lets the counter pass Limit" \
  "VACUOUS  vacuous: the unmutated model does not pass holds (Limit = 3) with BelowThree" -- \
  "$dir/mutants.py" "$fixture"
grep -oP 'TLC output: \K\S+' "$out" | xargs -r -n 1 dirname | xargs -r rm -rf

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
