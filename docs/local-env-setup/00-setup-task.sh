#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/versions.env"

case "$(uname -m)" in
  x86_64 | amd64)
    TASK_ARCH="amd64"
    TASK_SHA256="$TASK_LINUX_AMD64_SHA256"
    ;;
  aarch64 | arm64)
    TASK_ARCH="arm64"
    TASK_SHA256="$TASK_LINUX_ARM64_SHA256"
    ;;
  *)
    echo "エラー: 未対応のアーキテクチャです: $(uname -m)" >&2
    exit 1
    ;;
esac

if command -v task >/dev/null 2>&1 && task --version | grep -Fxq "$TASK_VERSION"; then
  echo "Task v${TASK_VERSION} は導入済みです。"
  exit 0
fi

TASK_ARCHIVE="task_linux_${TASK_ARCH}.tar.gz"
TASK_URL="https://github.com/go-task/task/releases/download/v${TASK_VERSION}/${TASK_ARCHIVE}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

echo "=== Installing Task v${TASK_VERSION} (${TASK_ARCH}) ==="
curl -fsSL "$TASK_URL" -o "$TEMP_DIR/$TASK_ARCHIVE"
printf '%s  %s\n' "$TASK_SHA256" "$TEMP_DIR/$TASK_ARCHIVE" | sha256sum --check --status

tar -xzf "$TEMP_DIR/$TASK_ARCHIVE" -C "$TEMP_DIR" task
install -d "$HOME/.local/bin"
install -m 0755 "$TEMP_DIR/task" "$HOME/.local/bin/task"

"$HOME/.local/bin/task" --version

echo ""
echo "=== 完了 ==="
echo "新しいシェルを開くか 'source ~/.bashrc' を実行して PATH を反映してください。"
