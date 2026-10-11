#!/usr/bin/env bash
# test.sh: runs the cleanup fixture Taskfile through Task and checks exit codes and which steps ran.
# Run: bash tooling/taskfile-cleanup/test.sh (task lint-taskfile runs it)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$dir/../.." && pwd)"
log="$(mktemp)"
trap 'rm -f "$log"' EXIT
failures=0

# expect <code> <steps> <label> [VAR=value...]
expect() {
  local want="$1" steps="$2" label="$3" code got
  shift 3
  : >"$log"
  task -x -d "$dir" run LOG="$log" "$@" >/dev/null 2>&1
  code=$?
  got="$(paste -sd ' ' "$log")"
  if [ "$code" != "$want" ] || [ "$got" != "$steps" ]; then
    echo "FAIL: $label (exit $code, want $want; steps '$got', want '$steps')"
    failures=$((failures + 1))
  fi
}

all="body body-rest clean1 clean2"
failed_body="body clean1 clean2"
expect 0 "$all" "body ok, cleanup ok"
expect 4 "$all" "body ok, cleanup fails" CLEAN2=4
expect 3 "$failed_body" "body fails, cleanup ok" BODY=3
expect 3 "$failed_body" "body and cleanup fail" BODY=3 CLEAN2=4
expect 5 "$all" "first cleanup step fails, the rest still runs" CLEAN1=5

# The old pattern turned errexit off around the body; keep it out of the real Taskfile.
if grep -n 'set +e' "$root/Taskfile.yml"; then
  echo "FAIL: Taskfile.yml uses 'set +e'; capture the body with '( set -e; ... ) || status=\$?' instead"
  failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
