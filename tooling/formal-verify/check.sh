#!/usr/bin/env bash
# Model-check a verification skill's TLA+ model with TLC (docs/agents/formal-verification.md).
# Run: tooling/formal-verify/check.sh <skill-dir> [cfg...]
# Checks <skill-dir>/model/<cfg>.cfg for each cfg named, or every model/*.cfg. The spec is the one .tla in model/.
# A cfg's first line is "\* expect: pass" or "\* expect: <invariant TLC must report>".
# Exit 0 when every cfg gives its expected result. A run's TLC output is kept only when it does not.
set -euo pipefail

tla_version="v1.7.4"
tla_sha256="936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88"
java_version="temurin-25.0.5+8.0.LTS"

if [ $# -lt 1 ]; then
  echo "usage: $0 <skill-dir> [cfg...]" >&2
  exit 2
fi
model="$1/model"
shift
shopt -s nullglob
specs=("$model"/*.tla)
if [ ${#specs[@]} -ne 1 ]; then
  echo "FAIL $model must hold exactly one .tla, found ${#specs[@]}" >&2
  exit 2
fi
spec="${specs[0]}"

# TLC's work files stay out of /tmp, which is a small tmpfs on some machines.
cache="${XDG_CACHE_HOME:-$HOME/.cache}/formal-verify-tla"
jar="$cache/tla2tools-$tla_version.jar"
mkdir -p "$cache/runs"
if ! { [ -f "$jar" ] && echo "$tla_sha256  $jar" | sha256sum -c --status; }; then
  curl -fsSL -o "$jar" "https://github.com/tlaplus/tlaplus/releases/download/$tla_version/tla2tools.jar"
  echo "$tla_sha256  $jar" | sha256sum -c --status
fi
mise install --quiet "java@$java_version" >/dev/null
java="$(mise where "java@$java_version")/bin/java"

configs=("$@")
if [ ${#configs[@]} -eq 0 ]; then
  for f in "$model"/*.cfg; do configs+=("$(basename "$f" .cfg)"); done
fi

status=0
for cfg in "${configs[@]}"; do
  file="$model/$cfg.cfg"
  expected="$( [ -f "$file" ] && sed -n '1s/^\\\* expect: //p' "$file" || true)"
  if [ -z "$expected" ]; then
    echo "FAIL $cfg: $file is missing or its first line is not '\\* expect: <pass or invariant>'"
    status=1
    continue
  fi
  out="$(mktemp -d "$cache/runs/$cfg.XXXXXX")"
  set +e
  "$java" -XX:+UseParallelGC -cp "$jar" tlc2.TLC -workers auto \
    -metadir "$out" -config "$file" "$spec" >"$out/tlc.out" 2>&1
  set -e
  violated="$(grep -oP '^Error: Invariant \K\S+(?= is violated)' "$out/tlc.out" || true)"
  if grep -q '^Model checking completed. No error has been found.' "$out/tlc.out"; then
    got=pass
  elif [ -n "$violated" ]; then
    got="$violated"
  elif grep -q '^Error: Deadlock reached' "$out/tlc.out"; then
    got=deadlock
  else
    got=error
  fi
  states="$(grep -oP '\d+(?= distinct states found)' "$out/tlc.out" | tail -1 || true)"
  if [ "$got" = "$expected" ]; then
    echo "OK   $cfg: $got (expected), ${states:-?} distinct states"
    rm -rf "$out"
  else
    echo "FAIL $cfg: got $got, expected $expected. TLC output: $out/tlc.out"
    grep -m 5 -iE 'error|is false' "$out/tlc.out" | sed 's/^/     /' || true
    status=1
  fi
done
exit $status
