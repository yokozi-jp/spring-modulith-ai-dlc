#!/bin/bash
set -e

# インストーラを一旦保存し、内容を確認できる状態にしてから実行する
# （curl | bash による無検証のパイプ実行を避ける）。
INSTALLER="$(mktemp)"
trap 'rm -f "$INSTALLER"' EXIT
curl -fsSL https://cli.kiro.dev/install -o "$INSTALLER"
bash "$INSTALLER"

echo ""
echo "=== 完了 ==="
echo "次のステップ："
echo "  ./04-setup-shell.sh を実行"
