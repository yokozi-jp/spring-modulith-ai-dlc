#!/usr/bin/env bash
# test-slot.sh: compose-test を使う実行（task test、task e2e、task e2e-up、task scan-dast）に slot を割り当てる。
# 使い方: eval "$(bash tooling/test-slot/test-slot.sh acquire <基にする env ファイル> [web])"
#        bash tooling/test-slot/test-slot.sh ports <slot>
# 判断の理由は docs/adr/ADR-104-*.md にある。

set -euo pipefail

SLOTS=4
# slot 0 は従来のポートのまま。slot n は 20000 + n * 1000 + 従来のポートの下 3 桁。
BASE_WEB=5173 BASE_BACKEND=8080 BASE_KEYCLOAK=8081 BASE_WIREMOCK=8082 BASE_PG=5433 BASE_REDIS=6380
PROJECT_PREFIX=spring-modulith-test
WAIT_SECONDS="${TEST_SLOT_WAIT_SECONDS:-900}"
POLL_SECONDS="${TEST_SLOT_POLL_SECONDS:-5}"
REPORT_SECONDS="${TEST_SLOT_REPORT_SECONDS:-30}"

port_of() { echo $(($1 == 0 ? $2 : 20000 + $1 * 1000 + $2 % 1000)); }

# 環境変数の契約。compose-test.yml、Taskfile.yml、frontend/apps/web が読む名前と一致させる。
ports() {
  local n="$1"
  echo "TEST_SLOT=$n"
  echo "COMPOSE_PROJECT_NAME=$PROJECT_PREFIX-$n"
  echo "E2E_WEB_PORT=$(port_of "$n" $BASE_WEB)"
  echo "TEST_BACKEND_PORT=$(port_of "$n" $BASE_BACKEND)"
  echo "TEST_KEYCLOAK_PORT=$(port_of "$n" $BASE_KEYCLOAK)"
  echo "TEST_WIREMOCK_PORT=$(port_of "$n" $BASE_WIREMOCK)"
  echo "TEST_PG_PORT=$(port_of "$n" $BASE_PG)"
  echo "TEST_REDIS_PORT=$(port_of "$n" $BASE_REDIS)"
}

# 基にする env ファイルのうち、ホストから見たポートを持つ変数だけを slot の値へ置き換えて書く。
write_env_file() {
  local n="$1" base="$2" out="$3" kc wm
  kc="$(port_of "$n" $BASE_KEYCLOAK)" wm="$(port_of "$n" $BASE_WIREMOCK)"
  mkdir -p "$(dirname "$out")"
  awk -F= -v db="$(port_of "$n" $BASE_PG)" -v redis="$(port_of "$n" $BASE_REDIS)" \
    -v server="$(port_of "$n" $BASE_BACKEND)" \
    -v issuer="http://127.0.0.1:$kc/realms/spring-modulith" -v payment="http://127.0.0.1:$wm" '
    BEGIN { v["DB_PORT"] = db; v["REDIS_PORT"] = redis; v["SERVER_PORT"] = server; v["OIDC_ISSUER_URI"] = issuer; v["PAYMENT_GATEWAY_BASE_URL"] = payment }
    $1 in v { print $1 "=" v[$1]; seen[$1] = 1; next }
    { print }
    END { for (k in v) if (!(k in seen)) print k "=" v[k] }
  ' "$base" >"$out.tmp"
  mv "$out.tmp" "$out"
}

emit() {
  local n="$1" base="$2" web="$3" quiet="${4:-}" file="build/test-slot/slot-$1.env" line
  write_env_file "$n" "$base" "$file"
  while IFS= read -r line; do echo "export $line"; done < <(ports "$n")
  echo "export TEST_ENV_FILE=$file"
  # vp preview と Playwright は、ルートの .env.test より環境変数を優先して読む。ホストから見たポートを持つ変数だけを渡す。
  # Gradle のテストへは渡さない。環境変数は Spring の env ファイルの import より優先され、テストのコンテナの接続先を上書きしてしまう。
  if [ -n "$web" ]; then
    for key in SERVER_PORT OIDC_ISSUER_URI PAYMENT_GATEWAY_BASE_URL; do
      echo "export $(grep "^$key=" "$file")"
    done
  fi
  [ -z "$quiet" ] || return 0
  echo "slot $n: web http://localhost:$(port_of "$n" $BASE_WEB)、backend http://localhost:$(port_of "$n" $BASE_BACKEND)、Keycloak http://127.0.0.1:$(port_of "$n" $BASE_KEYCLOAK)、WireMock http://127.0.0.1:$(port_of "$n" $BASE_WIREMOCK)（Compose project $PROJECT_PREFIX-$n）" >&2
}

# project のコンテナを「ID<TAB>持ち主の作業ディレクトリ」で列挙する。持ち主は Compose が付けるラベルで示す。
project_containers() {
  docker ps -a --filter "label=com.docker.compose.project=$1" \
    --format '{{.ID}}	{{.Label "com.docker.compose.project.working_dir"}}'
}

# 持ち主の worktree が消えた project の、コンテナ、volume、network を消す。
reclaim() {
  local project="$1" ids
  echo "持ち主の worktree が無い $project のコンテナを回収します。" >&2
  ids="$(project_containers "$project" | cut -f1)"
  # shellcheck disable=SC2086
  [ -z "$ids" ] || docker rm --force --volumes $ids >/dev/null
  for kind in volume network; do
    ids="$(docker "$kind" ls -q --filter "label=com.docker.compose.project=$project")"
    # shellcheck disable=SC2086
    [ -z "$ids" ] || docker "$kind" rm $ids >/dev/null
  done
}

docker() { command "${DOCKER:-docker}" "$@"; }

listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# 空いていれば 0、使用中なら理由を $busy_reason に入れて 1。
slot_free() {
  local n="$1" project="$PROJECT_PREFIX-$1" rows owner port
  rows="$(project_containers "$project")"
  if [ -n "$rows" ]; then
    owner="$(dirname "$(printf '%s\n' "$rows" | head -n1 | cut -f2)")"
    if [ -d "$owner" ]; then
      busy_reason="slot $n は $owner が使用中"
      return 1
    fi
    reclaim "$project"
  fi
  for port in $(ports "$n" | grep _PORT= | cut -d= -f2); do
    if [ "${TEST_SLOT_PORT_CHECK:-on}" = on ] && listening "$port"; then
      busy_reason="slot $n はポート $port が別のプロセスで使用中"
      return 1
    fi
  done
}

# 起動の前に最初のコンテナを作り、ロックを返した後も、ほかの実行から使用中に見えるようにする。
claim() {
  local n="$1"
  local output
  output="$(
    eval "$(ports "$n" | sed 's/^/export /')"
    docker compose -f "${TEST_COMPOSE_FILE:-docker/compose-test.yml}" create postgres 2>&1
  )" || { printf '%s\n' "$output" >&2; return 1; }
}

acquire() {
  local base="${1:?基にする env ファイルを指定してください。}" web="${2:-}" n deadline last_report=-999999 busy_reason lock
  cd "$(git rev-parse --show-toplevel)"
  if [ -n "${TEST_SLOT:-}" ]; then
    emit "$TEST_SLOT" "$base" "$web" reuse
    return
  fi
  # CI は 1 つの job が 1 台のマシンで動くので、いつも slot 0 を使う。
  if [ -n "${CI:-}" ]; then
    emit 0 "$base" "$web"
    return
  fi
  lock="$(git rev-parse --path-format=absolute --git-common-dir)/test-slot.lock"
  deadline=$((SECONDS + WAIT_SECONDS))
  while :; do
    local reasons=""
    exec 9>>"$lock"
    flock 9
    for ((n = 0; n < SLOTS; n++)); do
      if slot_free "$n"; then
        claim "$n"
        exec 9>&-
        emit "$n" "$base" "$web"
        return
      fi
      reasons+="  - $busy_reason"$'\n'
    done
    exec 9>&-
    if [ "$SECONDS" -ge "$deadline" ]; then
      printf '全ての slot が %s 秒を超えて使用中のため、取れませんでした。\n%s' "$WAIT_SECONDS" "$reasons" >&2
      return 1
    fi
    if [ $((SECONDS - last_report)) -ge "$REPORT_SECONDS" ]; then
      printf '全ての slot が使用中のため、空くまで待ちます（残り %s 秒）。\n%s' "$((deadline - SECONDS))" "$reasons" >&2
      last_report=$SECONDS
    fi
    sleep "$POLL_SECONDS"
  done
}

case "${1:-}" in
  acquire) shift; acquire "$@" ;;
  ports) ports "${2:?slot の番号を指定してください。}" ;;
  *) echo "使い方: test-slot.sh acquire <env ファイル> [web] | ports <slot>" >&2; exit 2 ;;
esac
