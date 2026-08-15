#!/bin/bash
set -e
source ./versions.env

# 環境変数設定
echo 'export PATH=".:$PATH"' >> ~/.bashrc
echo '' >> ~/.bashrc
echo 'cd /home' >> ~/.bashrc

echo ""
echo "=== 完了 ==="
