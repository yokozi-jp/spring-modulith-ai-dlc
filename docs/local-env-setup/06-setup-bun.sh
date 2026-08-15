#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/versions.env"

echo "=== Installing Bun v${BUN_VERSION} ==="
curl -fsSL https://bun.sh/install | bash -s "bun-v${BUN_VERSION}"

echo "=== Bun setup complete ==="
