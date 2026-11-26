#!/bin/bash
set -e

# インストーラーの質問を飛ばす。呼び出し元が先に決めた値（kiro-web.sh の VP_NODE_MANAGER=no）は上書きしない。
export VP_NODE_MANAGER="${VP_NODE_MANAGER:-yes}"
export VP_PM_MANAGER="${VP_PM_MANAGER:-yes}"

# インストーラを一旦保存し、内容を確認できる状態にしてから実行する
# （curl | bash による無検証のパイプ実行を避ける）。
INSTALLER="$(mktemp)"
trap 'rm -f "$INSTALLER"' EXIT
curl -fsSL https://vite.plus -o "$INSTALLER"
bash "$INSTALLER"

echo ""
echo "=== 完了 ==="
