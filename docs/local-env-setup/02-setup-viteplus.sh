#!/bin/bash
set -e

curl -fsSL https://vite.plus | bash

echo ""
echo "=== 完了 ==="
echo "インストール後、以下を実行してシェルを再読み込みしてください："
echo "  source ~/.bashrc"
echo ""
echo "次のステップ："
echo "  ./03-setup-kiro.sh を実行"
