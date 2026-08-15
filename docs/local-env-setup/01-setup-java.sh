#!/bin/bash
set -e
source ./versions.env

# Amazon Corretto リポジトリ追加
wget -O- https://apt.corretto.aws/corretto.key | sudo gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" | sudo tee /etc/apt/sources.list.d/corretto.list

# Corretto インストール
sudo apt-get update
sudo apt-get install -y ${JAVA_PACKAGE}=${JAVA_VERSION}

# 環境変数設定
echo 'export JAVA_HOME=/usr/lib/jvm/java-25-amazon-corretto' >> ~/.bashrc
echo 'export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"' >> ~/.bashrc

echo ""
echo "=== 完了 ==="
echo "Java version: $(java -version 2>&1 | head -1)"
echo ""
echo "次のステップ："
echo "  ./02-setup-viteplus.sh を実行"
