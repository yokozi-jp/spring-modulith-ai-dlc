#!/bin/bash
set -e

# 環境変数設定
# ~/.local/bin を PATH に通す（kiro-cli のインストール先）。
# ~/.profile はログインシェルでしか読まれないため、非ログインの対話シェルや
# 新しいターミナルでも kiro-cli を解決できるよう ~/.bashrc に追記する。
# 追記は冪等にし、再実行しても重複させない。
LOCAL_BIN_LINE='export PATH="$HOME/.local/bin:$PATH"'
if ! grep -qxF "$LOCAL_BIN_LINE" ~/.bashrc; then
  {
    echo ''
    echo '# ~/.local/bin (kiro-cli など)'
    echo "$LOCAL_BIN_LINE"
  } >> ~/.bashrc
fi

# エディタや Git フックから起動したプロセスにも PATH を通すため、activate を --shims で使う（ADR-090）。
MISE_SHIMS_LINE='eval "$(mise activate bash --shims)"'
if ! grep -qxF "$MISE_SHIMS_LINE" ~/.bashrc; then
  {
    echo ''
    echo '# mise の shims'
    echo "$MISE_SHIMS_LINE"
  } >> ~/.bashrc
fi

# 起動時に作業ディレクトリへ移動する（任意）。重複追記を避ける。
if ! grep -qxF 'cd /home' ~/.bashrc; then
  {
    echo ''
    echo 'cd /home'
  } >> ~/.bashrc
fi

echo ""
echo "=== 完了 ==="
