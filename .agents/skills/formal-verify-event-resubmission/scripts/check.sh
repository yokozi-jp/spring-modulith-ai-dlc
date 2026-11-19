#!/usr/bin/env bash
# Model-check the TLA+ model in model/ with TLC, once per model/*.cfg (or the configs named as arguments).
# Each cfg's first line is "\* expect: pass" or "\* expect: <invariant TLC must report>".
# Exit 0 when every config gives its expected result. MODEL_DIR points at another copy of model/
# and EXPECT overrides every expectation (both used by mutants.py).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
model="${MODEL_DIR:-$here/model}"
spec="$(basename "$(ls "$model"/*.tla)")"
tla_version="v1.7.4"
tla_sha256="936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88"
java_version="temurin-25.0.5+8.0.LTS"
cache="${XDG_CACHE_HOME:-$HOME/.cache}/formal-verify-tla"
jar="$cache/tla2tools-$tla_version.jar"

mkdir -p "$cache"
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
  expected="${EXPECT:-$(sed -n '1s/^\\\* expect: //p' "$model/$cfg.cfg")}"
  if [ -z "$expected" ]; then
    echo "FAIL $cfg: the first line of $model/$cfg.cfg is not '\\* expect: <pass or invariant>'"
    status=1
    continue
  fi
  mkdir -p "$cache/runs"
  out="$(mktemp -d "$cache/runs/$cfg.XXXXXX")"
  set +e
  "$java" -XX:+UseParallelGC -cp "$jar" tlc2.TLC -workers auto \
    -metadir "$out" -config "$model/$cfg.cfg" "$model/$spec" >"$out/tlc.out" 2>&1
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
    echo "OK   $cfg: $got (expected), ${states:-?} distinct states. TLC output: $out/tlc.out"
  else
    echo "FAIL $cfg: got $got, expected $expected. TLC output: $out/tlc.out"
    status=1
  fi
done
exit $status
