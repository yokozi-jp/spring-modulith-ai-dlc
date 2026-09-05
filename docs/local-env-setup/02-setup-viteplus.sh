#!/bin/bash
set -e

# インストーラを一旦保存し、内容を確認できる状態にしてから実行する
# （curl | bash による無検証のパイプ実行を避ける）。
INSTALLER="$(mktemp)"
trap 'rm -f "$INSTALLER"' EXIT
curl -fsSL https://vite.plus -o "$INSTALLER"
bash "$INSTALLER"

echo ""
echo "=== 完了 ==="
echo "インストール後、以下を実行してシェルを再読み込みしてください："
echo "  source ~/.bashrc"
echo ""
echo "次のステップ："
echo "  ./03-setup-kiro.sh を実行"
