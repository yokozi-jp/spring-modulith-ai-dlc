#!/usr/bin/env bash
# serve.sh: E2E の fixture サーバー（backend の E2eFixtureServer、ADR-129）を起動する。
set -euo pipefail

cd "$(dirname "$0")/../.."
args=backend/build/e2e-fixture/java-args.txt
java_path=backend/build/e2e-fixture/java-path.txt
if [ ! -f "$args" ] || [ ! -f "$java_path" ]; then
  echo "$args か $java_path がありません。task e2e-up を先に実行してください。" >&2
  exit 1
fi
set -a
. "${TEST_ENV_FILE:-.env.test}"
set +a
export E2E_FIXTURE_PORT="${E2E_FIXTURE_PORT:-8083}"
exec "$(cat "$java_path")" "@$args"
