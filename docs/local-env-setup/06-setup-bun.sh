#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/versions.env"

# インストーラを一旦保存し、内容を確認できる状態にしてから実行する
# （curl | bash による無検証のパイプ実行を避ける）。
INSTALLER="$(mktemp)"
trap 'rm -f "$INSTALLER"' EXIT
echo "=== Installing Bun v${BUN_VERSION} ==="
curl -fsSL https://bun.sh/install -o "$INSTALLER"
bash "$INSTALLER" -s "bun-v${BUN_VERSION}"

echo "=== Bun setup complete ==="
