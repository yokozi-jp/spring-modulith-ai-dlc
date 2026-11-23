#!/usr/bin/env bash
# check.sh: iwec の tools/list の全ツールが、残す読み取り系か mcp.json の disabledTools のどちらかに入っていることを確かめる。
# iwe を上げて書き換え系のツールが増えたとき、黙って通らないようにする。iwec が無い端末では何もしない。
# Run: bash tooling/iwe-tools/check.sh [mcp.json] (task okf-check が実行する)

set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
mcp="${1:-$root/.kiro/settings/mcp.json}"
kept="iwe_create iwe_find iwe_retrieve iwe_stats iwe_tree iwe_squash"

if ! command -v iwec >/dev/null; then
  echo "iwec が無いので iwe-tools-check を飛ばす"
  exit 0
fi

listed="$(
  printf '%s\n' \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"iwe-tools-check","version":"0"}}}' \
    '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
    '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' |
    (cd "$root/docs" && timeout 30 iwec 2>/dev/null) |
    jq -r 'select(.id == 2) | .result.tools[].name' | sort
)"
[ -n "$listed" ] || { echo "✗ iwec の tools/list が空" >&2; exit 1; }

disabled="$(jq -r '.mcpServers.iwe.disabledTools[]?' "$mcp")"
unknown="$(comm -23 <(printf '%s\n' "$listed") <(printf '%s\n%s\n' "$disabled" "${kept// /$'\n'}" | sort -u))"
if [ -n "$unknown" ]; then
  echo "✗ 次の iwe のツールが mcp.json の disabledTools にも、残す一覧にも入っていない:" >&2
  printf '  %s\n' $unknown >&2
  echo "  書き換えるツールなら ${mcp#"$root"/} の iwe.disabledTools へ足す。読み取りだけなら ${0#"$root"/} の kept へ足す。" >&2
  exit 1
fi
