#!/usr/bin/env bash
# gate.sh: 標準入力の needs の JSON（toJSON(needs)）を読み、すべてのジョブが success でなければ失敗にする。
# skipped、failure、cancelled はどれも失敗にする（docs/adr/ADR-087）。

set -euo pipefail

needs="$(cat)"
bad="$(jq -r 'to_entries[] | select(.value.result != "success") | "\(.key): \(.value.result)"' <<< "$needs")"
count="$(jq 'length' <<< "$needs")"

if [ "$count" -eq 0 ]; then
  echo "needs が空です。ゲートが何も待っていません。" >&2
  exit 1
fi
if [ -n "$bad" ]; then
  echo "success でないジョブがあります。" >&2
  echo "$bad" >&2
  exit 1
fi
echo "すべてのジョブが success です（${count} 件）。"
