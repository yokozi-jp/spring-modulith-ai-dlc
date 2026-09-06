#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/versions.env"

# アーキテクチャ判定（amd64 / arm64 に対応）
case "$(uname -m)" in
  x86_64 | amd64) GO_ARCH="amd64" ;;
  aarch64 | arm64) GO_ARCH="arm64" ;;
  *)
    echo "エラー: 未対応のアーキテクチャです: $(uname -m)" >&2
    exit 1
    ;;
esac

GO_TARBALL="go${GO_VERSION}.linux-${GO_ARCH}.tar.gz"

# Go の公式 tarball を取得して /usr/local に展開
echo "=== Installing Go ${GO_VERSION} (${GO_ARCH}) ==="
curl -fsSL "https://go.dev/dl/${GO_TARBALL}" -o "/tmp/${GO_TARBALL}"
sudo rm -rf /usr/local/go
sudo tar -C /usr/local -xzf "/tmp/${GO_TARBALL}"
rm -f "/tmp/${GO_TARBALL}"

# PATH を通す（このセッション用。source ~/.bashrc なしでも betterleaks を導入できるように）
export PATH="$PATH:/usr/local/go/bin:$HOME/go/bin"

# PATH を ~/.bashrc に永続化（冪等。再実行しても重複させない）
GO_PATH_LINE='export PATH=$PATH:/usr/local/go/bin:$HOME/go/bin'
if ! grep -qxF "$GO_PATH_LINE" ~/.bashrc; then
  {
    echo ''
    echo '# Go / betterleaks'
    echo "$GO_PATH_LINE"
  } >> ~/.bashrc
fi

go version

# betterleaks（シークレットスキャナ）をインストール
# go install はリリース用の ldflags を注入しないため、そのままだと
# `betterleaks version` が "dev" と表示される（機能は正常）。実バージョンを
# 解決して ldflags で埋め込み、正しいバージョンが表示されるようにする。
# -X で指すシンボルが将来変わっても Go リンカは黙って無視する（ビルドは失敗せず、
# 最悪 "dev" 表示に戻るだけ）ので安全。
BL_VERSION="$BETTERLEAKS_VERSION"
if [ "$BL_VERSION" = "latest" ]; then
  BL_VERSION="$(go list -m -f '{{.Version}}' github.com/betterleaks/betterleaks@latest)"
fi
echo "=== Installing betterleaks (${BL_VERSION}) ==="
go install \
  -ldflags "-X github.com/betterleaks/betterleaks/version.Version=${BL_VERSION}" \
  "github.com/betterleaks/betterleaks@${BL_VERSION}"
betterleaks version

echo ""
echo "=== 完了 ==="
echo "新しいシェルを開くか 'source ~/.bashrc' を実行して PATH を反映してください。"
