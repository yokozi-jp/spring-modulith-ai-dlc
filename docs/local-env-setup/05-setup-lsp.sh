#!/bin/bash
set -e
source ./versions.env

# VitePlus の PATH を設定（source ~/.bashrc なしでも動作するように）
# インストーラが生成する env（vp 関数と PATH を定義）を読み込む。
# bin の実体は $HOME/.local/share/vite-plus/bin。env が無い場合はそこを直接 PATH に追加する。
if [ -f "$HOME/.config/vite-plus/env" ]; then
  # shellcheck disable=SC1091
  . "$HOME/.config/vite-plus/env"
else
  export PATH="$HOME/.local/share/vite-plus/bin:$PATH"
fi

# vp が解決できることを確認（見つからなければ原因を明示して停止）
if ! command -v vp >/dev/null 2>&1; then
  echo "エラー: vp コマンドが見つかりません。" >&2
  echo "  先に ./02-setup-viteplus.sh を実行して VitePlus をインストールしてください。" >&2
  exit 1
fi

# TypeScript 言語サーバー
vp install -g typescript-language-server typescript

# Java 言語サーバー (Eclipse JDT Language Server)
curl -L -o /tmp/jdtls.tar.gz "https://download.eclipse.org/jdtls/milestones/${JDTLS_VERSION}/jdt-language-server-${JDTLS_VERSION}-${JDTLS_TIMESTAMP}.tar.gz"
mkdir -p ~/.local/share/jdtls
tar -xzf /tmp/jdtls.tar.gz -C ~/.local/share/jdtls
rm /tmp/jdtls.tar.gz
# 再実行しても重複追記しないようにガードする
JDTLS_PATH_LINE='export PATH="$HOME/.local/share/jdtls/bin:$PATH"'
grep -qxF "$JDTLS_PATH_LINE" ~/.bashrc || echo "$JDTLS_PATH_LINE" >> ~/.bashrc

echo ""
echo "=== 完了 ==="
echo "次のステップ："
echo "  source ~/.bashrc を実行"
