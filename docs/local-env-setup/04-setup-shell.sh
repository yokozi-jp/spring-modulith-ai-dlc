#!/bin/bash
set -e

# PATH を作業ディレクトリに左右させないため、activate を --shims で使う（ADR-090）。
# kiro-cli も task などと同じく、この shims から解決する。
# ログインシェルでは ~/.profile が ~/.bashrc の後で ~/.local/bin を PATH の先頭へ足すので、
# ~/.profile の末尾でも shims を先頭へ足し直す（ADR-090）。
# 追記は冪等にし、再実行しても重複させない。
MISE_SHIMS_LINE='eval "$(mise activate bash --shims)"'
for rc in ~/.bashrc ~/.profile; do
  if ! grep -qsxF "$MISE_SHIMS_LINE" "$rc"; then
    {
      echo ''
      echo '# mise の shims'
      echo "$MISE_SHIMS_LINE"
    } >> "$rc"
  fi
done

# 起動時に作業ディレクトリへ移動する（任意）。重複追記を避ける。
if ! grep -qxF 'cd /home' ~/.bashrc; then
  {
    echo ''
    echo 'cd /home'
  } >> ~/.bashrc
fi

echo ""
echo "=== 完了 ==="
