#!/bin/bash
set -e

# PATH を作業ディレクトリに左右させないため、activate を --shims で使う（ADR-090）。
# kiro-cli も task などと同じく、この shims から解決する。
# ~/.profile はログインシェルでしか読まれないため、非ログインの対話シェルや
# 新しいターミナルでも解決できるよう ~/.bashrc に追記する。
# 追記は冪等にし、再実行しても重複させない。
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
