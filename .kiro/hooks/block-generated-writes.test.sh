#!/usr/bin/env bash
# block-generated-writes.test.sh: feeds PreToolUse JSON to the hook and checks the exit code.
# Run: bash .kiro/hooks/block-generated-writes.test.sh

set -u

hook="$(cd "$(dirname "$0")" && pwd)/block-generated-writes.sh"
failures=0

# expect <code> <label> <stdin> [PATH]
expect() {
  local want="$1" label="$2" input="$3" path="${4:-$PATH}"
  local err code
  err="$(printf '%s' "$input" | PATH="$path" bash "$hook" 2>&1 >/dev/null)"
  code=$?
  if [ "$code" != "$want" ]; then
    echo "FAIL: $label (exit $code, want $want)"
    failures=$((failures + 1))
  elif [ "$want" = 2 ] && ! printf '%s' "$err" | grep -q 'regenerate'; then
    echo "FAIL: $label (stderr lacks regeneration steps: $err)"
    failures=$((failures + 1))
  fi
}

expect 2 "fs_write routeTree" '{"tool_name":"fs_write","tool_input":{"path":"frontend/src/routeTree.gen.ts"}}'
expect 2 "absolute path" '{"tool_name":"fs_write","tool_input":{"path":"/abs/x/frontend/src/routeTree.gen.ts"}}'
expect 2 "dot slash path" '{"tool_name":"fs_write","tool_input":{"path":"./frontend/src/routeTree.gen.ts"}}'
expect 2 "nested api generated" '{"tool_name":"fs_write","tool_input":{"path":"spring-modulith-ai-dlc/frontend/src/api/generated/endpoints/order/order.ts"}}'
expect 2 "str_replace path" '{"tool_name":"str_replace","tool_input":{"path":"frontend/src/routeTree.gen.ts"}}'
expect 2 "fsWrite filePath" '{"tool_name":"fsWrite","tool_input":{"filePath":"frontend/src/routeTree.gen.ts"}}'
expect 2 "delete_file targetFile" '{"tool_name":"delete_file","tool_input":{"targetFile":"frontend/src/api/generated/model/order.ts"}}'
expect 2 "write file_path" '{"tool_name":"write","tool_input":{"file_path":"frontend/src/routeTree.gen.ts"}}'

expect 0 "ordinary file" '{"tool_name":"fs_write","tool_input":{"path":"frontend/src/routes/index.tsx"}}'
expect 0 "fs_read routeTree" '{"tool_name":"fs_read","tool_input":{"path":"frontend/src/routeTree.gen.ts"}}'
expect 0 "read routeTree" '{"tool_name":"read","tool_input":{"file_path":"frontend/src/routeTree.gen.ts"}}'
expect 0 "empty stdin" ''
expect 0 "invalid json" '{not json'

# PATH without jq: only bash and cat.
nojq="$(mktemp -d)"
trap 'rm -rf "$nojq"' EXIT
ln -s "$(command -v bash)" "$nojq/bash"
ln -s "$(command -v cat)" "$nojq/cat"
expect 0 "missing jq" '{"tool_name":"fs_write","tool_input":{"path":"frontend/src/routeTree.gen.ts"}}' "$nojq"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
