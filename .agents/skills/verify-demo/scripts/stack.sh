#!/usr/bin/env bash
# 検証用の隔離スタック（compose-test の e2e profile と Vite preview）を起動、診断、片付けする。
# 使い方: stack.sh up | doctor | down
set -euo pipefail

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"
STATE=build/verify/stack
WEB=frontend/apps/web
APP_PATHS=(backend frontend docker openapi)
compose() { docker compose -f docker/compose-test.yml --profile e2e "$@"; }

# 起動した時点からアプリのソースが変わったかを見る指紋。検証スキルや docs の変更は含めない。
fingerprint() {
  { git rev-parse HEAD; git diff HEAD -- "${APP_PATHS[@]}"; git ls-files --others --exclude-standard -- "${APP_PATHS[@]}"; } | sha256sum | cut -d' ' -f1
}

preview_alive() {
  [ -f "$STATE/preview.pid" ] && kill -0 "$(cat "$STATE/preview.pid")" 2>/dev/null
}

port_free() {
  local rc=0
  curl --silent --output /dev/null --max-time 2 "http://localhost:$1/" || rc=$?
  [ "$rc" -eq 7 ]
}

up() {
  if [ -d "$STATE" ]; then
    echo "前回の検証スタックの状態（$STATE）が残っています。先に stack.sh down を実行してください。" >&2
    exit 1
  fi
  if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
    echo "Java がありません。docs/local-env-setup/setup.md の手順で Corretto 25 を入れてください（migration とシーダーは Gradle をホストで動かす）。" >&2
    exit 1
  fi
  if [ -n "$(compose ps -aq 2>/dev/null)" ]; then
    echo "Compose project spring-modulith-test が動いています。task test、task e2e、ほかの検証の実行中なら終わるまで待ち、自分が残したものなら stack.sh down を実行してください。" >&2
    exit 1
  fi
  for port in 5173 8080; do
    if ! port_free "$port"; then
      echo "localhost:$port は使用中です。開発用の Vite（task fe-dev）と Keycloak（task compose-up）を止めてから実行してください。" >&2
      exit 1
    fi
  done
  [ -f .env.test ] || cp .env.test.example .env.test

  mkdir -p "$STATE"
  fingerprint > "$STATE/fingerprint"
  date -u +%Y-%m-%dT%H:%M:%SZ > "$STATE/started-at"
  # task e2e の手順 2 から 4 と同じ（docs/e2e/testing-strategy.md の「実行と後片付け」）。
  (cd frontend && vp install --frozen-lockfile && vp exec --filter @frontend/web --fail-if-no-match vp build --mode test && vp exec --filter @frontend/web --fail-if-no-match playwright install chromium)
  compose build backend
  compose up -d --wait postgres redis keycloak wiremock
  task be-migrate COMPOSE_ENV_FILE=.env.test
  task be-seed COMPOSE_ENV_FILE=.env.test
  compose up -d --wait backend

  # 自分の process group で起動し、down がこの group だけを止める。
  # setsid の中の sh が process group の先頭なので、その $$ を記録する。
  (cd "$WEB" && PIDFILE="$ROOT/$STATE/preview.pid" setsid sh -c 'echo $$ > "$PIDFILE"; exec vp preview --mode test --port 5173 --strictPort' > "$ROOT/$STATE/preview.log" 2>&1 < /dev/null &)
  for _ in $(seq 1 10); do [ -s "$STATE/preview.pid" ] && break; sleep 0.5; done
  for _ in $(seq 1 60); do
    if curl --fail --silent --max-time 2 http://localhost:5173/actuator/health/readiness >/dev/null; then
      echo "検証スタックの準備ができました: http://localhost:5173"
      return 0
    fi
    sleep 1
  done
  echo "60 秒待っても preview から backend の readiness が返りません。$STATE/preview.log を確かめてください。" >&2
  exit 1
}

check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "OK   $name"; else echo "FAIL $name"; failed=1; fi
}

doctor() {
  failed=0
  check "このスクリプトが起動したスタックの状態がある（$STATE）" test -f "$STATE/fingerprint"
  check "自分が起動した Vite preview が生きている" preview_alive
  check "preview が SPA を配信する（localhost:5173）" sh -c 'curl --fail --silent --max-time 3 http://localhost:5173/ | grep -q "<title>デモアプリケーション</title>"'
  check "preview の proxy から backend が ready" curl --fail --silent --max-time 3 http://localhost:5173/actuator/health/readiness
  check "Keycloak の issuer が http://127.0.0.1:8081/realms/spring-modulith" sh -c 'curl --fail --silent --max-time 3 http://127.0.0.1:8081/realms/spring-modulith/.well-known/openid-configuration | grep -q "\"issuer\":\"http://127.0.0.1:8081/realms/spring-modulith\""'
  check "決済代行の WireMock の管理 API が応答する（127.0.0.1:8082）" curl --fail --silent --max-time 3 http://127.0.0.1:8082/__admin/mappings
  check "代表データ（SEED-C01 から SEED-C06）が入っている" sh -c "docker compose -f docker/compose-test.yml exec -T postgres psql -U demo -d demo -tAc \"select count(*) from ordering.t_order where customer_order_code like 'SEED-C0%'\" | grep -qx 6"
  if [ -f "$STATE/fingerprint" ] && [ "$(cat "$STATE/fingerprint")" != "$(fingerprint)" ]; then
    echo "FAIL 起動後に ${APP_PATHS[*]} が変わった（古いビルドを検証しないよう down と up をやり直す）"
    failed=1
  fi
  [ "$failed" -eq 0 ] && echo "doctor: 検証できる状態です。" || { echo "doctor: 検証できる状態ではありません。" >&2; exit 1; }
}

down() {
  if [ ! -d "$STATE" ]; then
    echo "$STATE がないため、このスクリプトが起動したスタックはありません。ほかの実行の compose-test は止めません。" >&2
    exit 0
  fi
  mkdir -p build/verify/logs
  log="build/verify/logs/backend-$(date -u +%Y%m%dT%H%M%SZ).log"
  compose logs --no-color backend > "$log" 2>&1 || true
  cp "$STATE/preview.log" "${log%backend-*}preview-${log##*backend-}" 2>/dev/null || true
  if preview_alive; then
    kill -- "-$(cat "$STATE/preview.pid")"
    for _ in $(seq 1 20); do port_free 5173 && break; sleep 0.5; done
  fi
  if ! port_free 5173; then
    echo "自分が起動した preview の process group（$(cat "$STATE/preview.pid")）を止めても 5173 が応答します。$STATE を残すので、ss -ltnp で持ち主を確かめてください。" >&2
    exit 1
  fi
  compose down --volumes --remove-orphans
  rm -rf "$STATE"
  echo "片付けました。backend のログ: $log。証拠は build/verify/runs/ に残っています。"
}

case "${1:-}" in
  up) up ;;
  doctor) doctor ;;
  down) down ;;
  *) echo "使い方: $0 up | doctor | down" >&2; exit 2 ;;
esac
