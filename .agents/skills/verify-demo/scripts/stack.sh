#!/usr/bin/env bash
# 検証用の隔離スタック（compose-test の e2e profile と Vite preview）を起動、診断、片付けする。
# 使い方: stack.sh up | doctor | down
set -euo pipefail

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"
STATE=build/verify/stack
WEB=frontend/apps/web
APP_PATHS=(backend frontend docker openapi)
SLOT_SH=tooling/test-slot/test-slot.sh
# Compose project と各ポートは、up が取った slot の環境変数（$STATE/slot.env、acquire の出力そのもの）から決める。
load_slot() { . "$STATE/slot.env"; }
compose() { docker compose -f docker/compose-test.yml --profile e2e "$@"; }

# 起動した時点からアプリのソースが変わったかを見る指紋。Git の管理外のファイルは中身も含める。
fingerprint() {
  {
    git rev-parse HEAD
    git diff HEAD -- "${APP_PATHS[@]}"
    git ls-files -z --others --exclude-standard -- "${APP_PATHS[@]}" | xargs -0 -r sha256sum
  } | sha256sum | cut -d' ' -f1
}

containers() { compose ps -aq 2>/dev/null | sort; }

# 起動が途中で打ち切られ、コンテナの記録がないときの判定。
# up は空の project を確かめてから状態を作るので、状態の後に作られたコンテナは、この起動のものとみなす。
containers_created_since_up() {
  local started id created
  started="$(stat -c %Y "$STATE/boot-id")"
  [ -n "$(containers)" ] || return 1
  for id in $(containers); do
    created="$(date -d "$(docker inspect -f '{{.Created}}' "$id")" +%s)"
    [ "$created" -ge "$started" ] || return 1
  done
}

port_free() {
  local rc=0
  curl --silent --output /dev/null --max-time 2 "http://localhost:$1/" || rc=$?
  [ "$rc" -eq 7 ]
}

# 第 22 欄（起動時刻）。第 2 欄のコマンド名は空白を含みうるので、最後の ) より後ろを数える。
start_time() { sed 's/.*) //' "/proc/$1/stat" | cut -d' ' -f20; }

# 記録した PID が、この起動でこのスクリプトが立てた preview の process group の先頭であるときだけ真。
# 再起動の後や PID の再利用で、無関係のプロセスを止めないため。
preview_ours() {
  [ -f "$STATE/preview.pid" ] || return 1
  local pid
  pid="$(cat "$STATE/preview.pid")"
  [ -d "/proc/$pid" ] || return 1
  [ "$(cat /proc/sys/kernel/random/boot_id)" = "$(cat "$STATE/boot-id")" ] || return 1
  [ "$(start_time "$pid")" = "$(cat "$STATE/preview.start")" ] || return 1
  [ "$(ps -o pgid= -p "$pid" | tr -d ' ')" = "$pid" ] || return 1
  tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q "vp preview"
}

# compose-test のコンテナが、up が記録した自分のものと同じときだけ真。
containers_ours() {
  [ -s "$STATE/containers" ] && [ "$(containers)" = "$(cat "$STATE/containers")" ]
}

up() {
  if [ -d "$STATE" ]; then
    echo "前回の検証スタックの状態（$STATE）が残っています。先に stack.sh down を実行してください。" >&2
    exit 1
  fi

  # task e2e-up の _ensure-test-env と同じ。slot の env ファイルが .env.test の写しなので、その前に必要になる。
  [ -f .env.test ] || cp .env.test.example .env.test
  mkdir -p "$STATE"
  fingerprint > "$STATE/fingerprint"
  cat /proc/sys/kernel/random/boot_id > "$STATE/boot-id"
  # 空きの slot を取る。空きがなければ待ち、待っている間は slot を持つ worktree を出す。
  # 取った slot の Compose project は、この起動のものとして up の記録より後に作られる。
  # 環境に TEST_SLOT があると acquire は空きの確認なしにその slot を使うので、必ず新しく取る。
  # ponytail: 取った直後から slot.env を書くまでの間に打ち切られると、取った slot のコンテナが残り、down では片付かない。
  unset TEST_SLOT
  local slot_env
  slot_env="$(bash "$SLOT_SH" acquire .env.test web)" || { rm -rf "$STATE"; exit 1; }
  printf '%s\n' "$slot_env" > "$STATE/slot.env"
  load_slot
  # 起動の手順は task e2e と共有する。失敗しても、作りかけのコンテナを down が自分のものと分かるよう記録する。
  local status=0
  task e2e-up || status=$?
  containers > "$STATE/containers"
  if [ "$status" -ne 0 ]; then
    echo "task e2e-up が失敗しました。ログを読んだら stack.sh down で片付けてください。" >&2
    exit "$status"
  fi

  # setsid の中の sh が process group の先頭なので、その $$ を記録する。
  (cd "$WEB" && PIDFILE="$ROOT/$STATE/preview.pid" setsid sh -c 'echo $$ > "$PIDFILE"; exec vp preview --mode test --port "$E2E_WEB_PORT" --strictPort' > "$ROOT/$STATE/preview.log" 2>&1 < /dev/null &)
  for _ in $(seq 1 10); do [ -s "$STATE/preview.pid" ] && break; sleep 0.5; done
  start_time "$(cat "$STATE/preview.pid")" > "$STATE/preview.start"
  for _ in $(seq 1 60); do
    if curl --fail --silent --max-time 2 "http://localhost:$E2E_WEB_PORT/actuator/health/readiness" >/dev/null; then
      echo "検証スタックの準備ができました（slot $TEST_SLOT）: http://localhost:$E2E_WEB_PORT"
      return 0
    fi
    sleep 1
  done
  echo "60 秒待っても preview から backend の readiness が返りません。$STATE/preview.log を確かめてください。" >&2
  exit 1
}

serves_spa() { curl --fail --silent --max-time 3 "http://localhost:$E2E_WEB_PORT/" | grep -q "<title>デモアプリケーション</title>"; }
backend_ready() { curl --fail --silent --max-time 3 "http://localhost:$E2E_WEB_PORT/actuator/health/readiness"; }
keycloak_issuer() {
  curl --fail --silent --max-time 3 "http://127.0.0.1:$TEST_KEYCLOAK_PORT/realms/spring-modulith/.well-known/openid-configuration" \
    | grep -q "\"issuer\":\"http://127.0.0.1:$TEST_KEYCLOAK_PORT/realms/spring-modulith\""
}
wiremock_admin() { curl --fail --silent --max-time 3 "http://127.0.0.1:$TEST_WIREMOCK_PORT/__admin/mappings"; }
# アプリのロールは業務のスキーマを読めないので、モジュールのロールへ切り替える（ADR-094）。
seeded_orders() {
  printf '%s\n' "SET ROLE ordering_module;" "select count(*) from ordering.t_order where customer_order_code like 'SEED-C0%';" \
    | compose exec -T postgres psql -U demo -d demo -v ON_ERROR_STOP=1 -qtA | grep -qx 6
}
source_unchanged() { [ "$(cat "$STATE/fingerprint")" = "$(fingerprint)" ]; }

check() {
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "OK   $name"; else echo "FAIL $name"; failed=1; fi
}

doctor() {
  failed=0
  if [ ! -d "$STATE" ]; then
    echo "FAIL このスクリプトが起動したスタックの状態がない（$STATE）。stack.sh up を実行する" >&2
    exit 1
  fi
  if [ ! -s "$STATE/slot.env" ]; then
    echo "FAIL $STATE に slot の記録（slot.env）がない。stack.sh down の後に up をやり直す" >&2
    exit 1
  fi
  load_slot
  check "compose-test のコンテナが up の記録と同じ" containers_ours
  check "自分が起動した Vite preview が生きている" preview_ours
  check "preview が SPA を配信する（localhost:$E2E_WEB_PORT）" serves_spa
  check "preview の proxy から backend が ready" backend_ready
  check "Keycloak の issuer が http://127.0.0.1:$TEST_KEYCLOAK_PORT/realms/spring-modulith" keycloak_issuer
  check "決済代行の WireMock の管理 API が応答する（127.0.0.1:$TEST_WIREMOCK_PORT）" wiremock_admin
  check "代表の注文（SEED-C01 から SEED-C06）が入っている" seeded_orders
  check "起動後に ${APP_PATHS[*]} が変わっていない（変わったら down と up をやり直す）" source_unchanged
  if [ "$failed" -ne 0 ]; then
    echo "doctor: 検証できる状態ではありません。" >&2
    exit 1
  fi
  echo "doctor: 検証できる状態です。"
}

down() {
  if [ ! -d "$STATE" ]; then
    echo "$STATE がないため、このスクリプトが起動したスタックはありません。ほかの実行の compose-test は止めません。" >&2
    exit 0
  fi
  # slot を取る前に打ち切られた起動には、止める preview も片付けるコンテナもない。
  if [ ! -s "$STATE/slot.env" ]; then
    echo "$STATE に slot の記録（slot.env）がありません（slot を取る前に打ち切られた起動）。$STATE だけを消します。" >&2
    rm -rf "$STATE"
    exit 0
  fi
  load_slot
  local status=0 stamp
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p build/verify/logs
  cp "$STATE/preview.log" "build/verify/logs/preview-$stamp.log" 2>/dev/null || true

  if preview_ours; then
    kill -- "-$(cat "$STATE/preview.pid")"
    for _ in $(seq 1 20); do port_free "$E2E_WEB_PORT" && break; sleep 0.5; done
    if ! port_free "$E2E_WEB_PORT"; then
      echo "自分が起動した preview の process group（$(cat "$STATE/preview.pid")）を止めても $E2E_WEB_PORT が応答します。$STATE を残すので、ss -ltnp で持ち主を確かめてください。" >&2
      exit 1
    fi
  elif ! port_free "$E2E_WEB_PORT"; then
    echo "自分が起動した preview は動いていませんが、$E2E_WEB_PORT を別のプロセスが使っています。止めずに続けます。" >&2
  fi

  if containers_ours || { [ ! -e "$STATE/containers" ] && containers_created_since_up; }; then
    task e2e-collect-logs LOG="build/verify/logs/backend-$stamp.log" || status=$?
    compose down --volumes --remove-orphans
  elif [ -n "$(containers)" ]; then
    echo "compose-test のコンテナが up の記録と違います（ほかの実行が立て直した可能性があります）。止めずに $STATE だけを消します。" >&2
  fi
  rm -rf "$STATE"
  echo "片付けました。ログは build/verify/logs/、証拠は build/verify/runs/ に残っています。"
  exit "$status"
}

case "${1:-}" in
  up) up ;;
  doctor) doctor ;;
  down) down ;;
  *) echo "使い方: $0 up | doctor | down" >&2; exit 2 ;;
esac
