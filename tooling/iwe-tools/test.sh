#!/usr/bin/env bash
# test.sh: check.sh が、disabledTools から 1 本外すと失敗し、戻すと通ることを確かめる。
# Run: bash tooling/iwe-tools/test.sh (task okf-check が実行する)

set -u
command -v iwec >/dev/null || { echo "iwec が無いので iwe-tools-check の自己検査を飛ばす"; exit 0; }

dir="$(dirname "$0")"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
all='["iwe_normalize","iwe_rename","iwe_update","iwe_extract","iwe_inline","iwe_attach","iwe_delete","iwe_query"]'
failures=0

# expect <説明> <終了コード> <disabledTools の JSON>
expect() {
  jq -n --argjson t "$3" '{mcpServers:{iwe:{disabledTools:$t}}}' >"$tmp/mcp.json"
  bash "$dir/check.sh" "$tmp/mcp.json" >/dev/null 2>&1
  code=$?
  if [ "$code" != "$2" ]; then
    echo "FAIL: $1 (exit $code, want $2)"
    failures=$((failures + 1))
  fi
}

expect "all eight hidden passes" 0 "$all"
expect "iwe_query exposed fails" 1 "$(jq -c 'map(select(. != "iwe_query"))' <<<"$all")"
expect "iwe_normalize exposed fails" 1 "$(jq -c 'map(select(. != "iwe_normalize"))' <<<"$all")"
exit $((failures > 0))
