#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/versions.env"

# iwe は Markdown ナレッジ管理ツール（CLI / LSP / MCP）で、docs/ を OKF v0.2 バンドルとして
# 検証する task okf-check が利用する。npm でグローバルインストールし、バージョンは
# versions.env の IWE_VERSION に固定する。
echo "=== Installing iwe v${IWE_VERSION} ==="

# 02-setup-viteplus.sh は Vite+ の PATH を ~/.bashrc に追記するだけなので、
# task setup のように同じシェルで続けて実行すると npm が見えない。
# Vite+ が ~/.config/vite-plus/env で通すディレクトリを、ここでも PATH に加える。
export PATH="$HOME/.local/share/vite-plus/bin:$PATH:$HOME/.local/share/vite-plus/fallback-bin"

if ! command -v npm >/dev/null 2>&1; then
  echo "npm が見つかりません。先に ./02-setup-viteplus.sh を実行して Node.js を導入してください。" >&2
  exit 1
fi

npm install -g "@iwe-org/iwe@${IWE_VERSION}"

echo "=== iwe setup complete ==="
iwe --version
