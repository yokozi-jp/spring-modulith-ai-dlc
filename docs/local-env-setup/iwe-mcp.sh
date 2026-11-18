#!/bin/bash
# iwe の MCP サーバー（iwec）を docs/ で起動する。.kiro/settings/mcp.json が呼ぶ。
# 標準出力は MCP の通信なので、準備の出力はすべて標準エラーへ出す。
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ -d /inner_container ]; then
  # Kiro Web は MCP サーバーを tool call より先に起動し、そのときは iwe が PATH に無い（ADR-093）。
  # docker の代わりのサービスは MCP に要らないので起動しない。
  KIRO_WEB_NO_DOCKER=1 source "$repo/docs/local-env-setup/kiro-web.sh" >&2
fi
cd "$repo/docs"
exec iwec
