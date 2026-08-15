#!/bin/bash
set -e
source ./versions.env

# TypeScript 言語サーバー
vp install -g typescript-language-server typescript
echo 'export PATH="$HOME/.vite-plus/bin:$PATH"' >> ~/.bashrc

# Java 言語サーバー (Eclipse JDT Language Server)
curl -L -o /tmp/jdtls.tar.gz "https://download.eclipse.org/jdtls/milestones/${JDTLS_VERSION}/jdt-language-server-${JDTLS_VERSION}-${JDTLS_TIMESTAMP}.tar.gz"
mkdir -p ~/.local/share/jdtls
tar -xzf /tmp/jdtls.tar.gz -C ~/.local/share/jdtls
rm /tmp/jdtls.tar.gz
echo 'export PATH="$HOME/.local/share/jdtls/bin:$PATH"' >> ~/.bashrc

echo ""
echo "=== 完了 ==="
echo "次のステップ："
echo "  source ~/.bashrc を実行"
