#!/usr/bin/env bash
# test.sh: check.sh が、disabledTools から 1 本外すと失敗し、戻すと通ることを確かめる。
# 外す対象は、実際の mcp.json の disabledTools から取る。
# Run: bash tooling/iwe-tools/test.sh (task okf-check が実行する)

set -u

dir="$(dirname "$0")"
real="$dir/../../.kiro/settings/mcp.json"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

# expect <説明> <終了コード> <disabledTools の jq 式>
expect() {
  jq "{mcpServers:{iwe:{disabledTools:(.mcpServers.iwe.disabledTools | $3)}}}" "$real" >"$tmp/mcp.json"
  bash "$dir/check.sh" "$tmp/mcp.json" >/dev/null 2>&1
  code=$?
  if [ "$code" != "$2" ]; then
    echo "FAIL: $1 (exit $code, want $2)"
    failures=$((failures + 1))
  fi
}

expect "the real list passes" 0 '.'
for tool in $(jq -r '.mcpServers.iwe.disabledTools[]' "$real"); do
  expect "exposing $tool fails" 1 "map(select(. != \"$tool\"))"
done
expect "hiding a kept tool fails" 1 '. + ["iwe_find"]'
exit $((failures > 0))
