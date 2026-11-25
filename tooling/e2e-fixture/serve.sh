#!/usr/bin/env bash
# serve.sh: E2E の fixture サーバー（backend の E2eFixtureServer、ADR-129）を起動する。
# Playwright の webServer が起動し、終了時に止める。java を exec するので、止める相手は java のプロセスそのものになる。
# 引数ファイルは task e2e-up が backend の Gradle の writeE2eFixtureArgs で書く。
set -euo pipefail

cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
args=backend/build/e2e-fixture/java-args.txt
if [ ! -f "$args" ]; then
  echo "$args がありません。task e2e-up を先に実行してください。" >&2
  exit 1
fi
# slot の env ファイル（test の目印、loopback の DB_HOST、slot の DB_PORT と issuer）から接続先を読む。
# vp run e2e を slot の環境変数なしで実行したときは、slot 0 の値を持つ .env.test を読む。
set -a
. "${TEST_ENV_FILE:-.env.test}"
set +a
export E2E_FIXTURE_PORT="${E2E_FIXTURE_PORT:-8083}"
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" "@$args"
