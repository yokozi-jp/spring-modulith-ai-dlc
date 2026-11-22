#!/usr/bin/env bash
# TenantContextExhaustiveTest だけを流す。リポジトリのどこからでも実行できる。
set -euo pipefail
cd "$(git rev-parse --show-toplevel)/backend"
./gradlew test --tests '*TenantContextExhaustiveTest' -PtestEnvFile=../.env.test
