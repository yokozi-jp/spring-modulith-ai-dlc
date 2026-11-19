#!/usr/bin/env bash
# test.sh: 偽の docker を PATH に置き、test-slot.sh の slot の割り当て、待ち、回収、環境変数の契約と、realm の origin の数を確かめる。
# Run: bash tooling/test-slot/test.sh (task test-slot-test が実行する)

set -u
. "$(dirname "$0")/../scratch-git/scratch-git.sh" || exit 1

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

check() {
  if [ "$2" = "$3" ]; then echo "ok: $1"; else echo "FAIL: $1 (期待 [$2]、実際 [$3])"; failures=$((failures + 1)); fi
}

work="$tmp/work"
mkdir -p "$work/docker" "$tmp/bin"
git init -q "$work"
cp "$repo_root/.env.test.example" "$work/.env.test"
touch "$work/docker/compose-test.yml"

# 偽の docker。コンテナは state に「project TAB ID TAB 持ち主の作業ディレクトリ」で持つ。
state="$tmp/containers"
cat >"$tmp/bin/docker" <<'EOF'
#!/usr/bin/env bash
state="$FAKE_DOCKER_STATE"
case "$1 $2" in
  "ps -a")
    project="${4#label=com.docker.compose.project=}"
    awk -F'\t' -v p="$project" '$1 == p { print $2 "\t" $3 }' "$state"
    ;;
  "rm --force")
    for id in "${@:4}"; do sed -i "/\t$id\t/d" "$state"; done
    echo "$*" >>"$FAKE_DOCKER_STATE.log"
    ;;
  "volume ls" | "network ls") ;;
  "compose -f")
    printf '%s\tc%s\t%s\n' "$COMPOSE_PROJECT_NAME" "$RANDOM$RANDOM" "$FAKE_OWNER/docker" >>"$state"
    ;;
esac
EOF
chmod +x "$tmp/bin/docker"
: >"$state"

export PATH="$tmp/bin:$PATH" FAKE_DOCKER_STATE="$state" FAKE_OWNER="$tmp/owner"
# 動いている開発環境のポートに左右されないよう、ポートの検査は止める。
export TEST_SLOT_POLL_SECONDS=1 TEST_SLOT_REPORT_SECONDS=1 TEST_SLOT_PORT_CHECK=off
mkdir -p "$tmp/owner"
unset CI TEST_SLOT

# acquire [env...]: work の中で slot を取り、export の行を返す。
acquire() { (cd "$work" && env "$@" bash "$here/test-slot.sh" acquire .env.test web 2>"$tmp/stderr"); }
slot_of() { printf '%s\n' "$1" | sed -n 's/^export TEST_SLOT=//p'; }
value_of() { printf '%s\n' "$1" | sed -n "s/^export $2=//p"; }
contains() { grep -q -F -- "$2" "$1" && echo yes || echo no; }
hold() { printf 'spring-modulith-test-%s\tfake%s\t%s\n' "$1" "$1" "${2:-$tmp/owner}/docker" >>"$state"; }

out="$(acquire)"
check "空なら slot 0" 0 "$(slot_of "$out")"
check "slot 0 は従来の PostgreSQL のポート" 5433 "$(value_of "$out" TEST_PG_PORT)"
check "slot 0 は従来の web のポート" 5173 "$(value_of "$out" E2E_WEB_PORT)"
check "取った slot は使用中に見える" 1 "$(grep -c '^spring-modulith-test-0	' "$state")"

out="$(acquire)"
check "slot 0 が使用中なら slot 1" 1 "$(slot_of "$out")"
check "slot 1 の PostgreSQL" 21433 "$(value_of "$out" TEST_PG_PORT)"
check "slot 1 の Redis" 21380 "$(value_of "$out" TEST_REDIS_PORT)"
check "slot 1 の backend" 21080 "$(value_of "$out" TEST_BACKEND_PORT)"
check "slot 1 の Keycloak" 21081 "$(value_of "$out" TEST_KEYCLOAK_PORT)"
check "slot 1 の WireMock" 21082 "$(value_of "$out" TEST_WIREMOCK_PORT)"
check "slot 1 の web" 21173 "$(value_of "$out" E2E_WEB_PORT)"
check "slot 1 の Compose project" spring-modulith-test-1 "$(value_of "$out" COMPOSE_PROJECT_NAME)"
check "slot 1 の issuer" "http://127.0.0.1:21081/realms/spring-modulith" "$(value_of "$out" OIDC_ISSUER_URI)"

env_file="$work/build/test-slot/slot-1.env"
check "env ファイルの DB_PORT" "DB_PORT=21433" "$(grep '^DB_PORT=' "$env_file")"
check "env ファイルの REDIS_PORT" "REDIS_PORT=21380" "$(grep '^REDIS_PORT=' "$env_file")"
check "env ファイルの決済代行" "PAYMENT_GATEWAY_BASE_URL=http://127.0.0.1:21082" "$(grep '^PAYMENT_GATEWAY_BASE_URL=' "$env_file")"
check "env ファイルの SERVER_PORT" "SERVER_PORT=21080" "$(grep '^SERVER_PORT=' "$env_file")"
check "ポート以外の変数は基の値のまま" "$(grep '^OIDC_CLIENT_ID=' "$work/.env.test")" "$(grep '^OIDC_CLIENT_ID=' "$env_file")"
check "基のファイルは書き換えない" "$(grep '^DB_PORT=' "$repo_root/.env.test.example")" "$(grep '^DB_PORT=' "$work/.env.test")"

no_web="$(cd "$work" && TEST_SLOT=1 bash "$here/test-slot.sh" acquire .env.test 2>/dev/null)"
check "web を指定しなければ接続先の変数を渡さない" 0 "$(printf '%s\n' "$no_web" | grep -cE 'OIDC_ISSUER_URI|PAYMENT_GATEWAY_BASE_URL|SERVER_PORT')"

out="$(acquire TEST_SLOT=3)"
check "TEST_SLOT があればその slot を使う" 3 "$(slot_of "$out")"
check "TEST_SLOT で slot を取り直さない" 0 "$(grep -c '^spring-modulith-test-3	' "$state")"

out="$(acquire CI=true)"
check "CI はいつも slot 0" 0 "$(slot_of "$out")"

: >"$state"
hold 0 "$tmp/gone"
out="$(acquire)"
check "持ち主の worktree が無ければ回収して slot 0" 0 "$(slot_of "$out")"
check "回収したコンテナを消した" 1 "$(grep -c 'rm --force' "$state.log")"

: >"$state"
for n in 0 1 2 3; do hold "$n"; done
out="$(acquire TEST_SLOT_WAIT_SECONDS=2)"
check "全て使用中なら失敗する" "" "$out"
status=0
(cd "$work" && TEST_SLOT_WAIT_SECONDS=2 bash "$here/test-slot.sh" acquire .env.test >/dev/null 2>"$tmp/stderr") || status=$?
check "待ちの上限を超えたら終了コード 1" 1 "$status"
check "待っていることを出力する" yes "$(contains "$tmp/stderr" '空くまで待ちます')"
check "持ち主の worktree を出力する" yes "$(contains "$tmp/stderr" "$tmp/owner")"

: >"$state"
for n in 0 1 2 3; do hold "$n"; done
(sleep 2; sed -i '/^spring-modulith-test-2\t/d' "$state") &
out="$(acquire TEST_SLOT_WAIT_SECONDS=20)"
wait
check "空いたら待っていた実行が取る" 2 "$(slot_of "$out")"

: >"$state"
for i in 1 2 3 4 5; do
  (cd "$work" && TEST_SLOT_WAIT_SECONDS=3 bash "$here/test-slot.sh" acquire .env.test 2>/dev/null | sed -n 's/^export TEST_SLOT=//p' >"$tmp/par-$i") &
done
wait
check "同時の 5 本のうち 4 本が別々の slot を取る" "0 1 2 3" "$(cat "$tmp"/par-* | grep . | sort | tr '\n' ' ' | sed 's/ $//')"
check "5 本目は取れない" 1 "$(grep -L . "$tmp"/par-* | wc -l | tr -d ' ')"

# realm の origin の数が slot の数と一致する（Keycloak はポートのワイルドカードを扱えないので、全ての slot の origin を並べる）。
slots="$(sed -n 's/^SLOTS=//p' "$here/test-slot.sh")"
realm="$repo_root/docker/keycloak/realm.json"
for ((n = 0; n < slots; n++)); do
  port="$(bash "$here/test-slot.sh" ports "$n" | sed -n 's/^E2E_WEB_PORT=//p')"
  for uri in "http://localhost:$port/login/oauth2/code/web" "http://localhost:$port" "http://localhost:$port/logged-out"; do
    check "realm に slot $n の $uri がある" yes "$(contains "$realm" "$uri")"
  done
done
check "realm の web の origin の数は slot の数と同じ" "$slots" \
  "$(grep -oE '"http://localhost:(5|2[0-9])173"' "$realm" | sort -u | wc -l | tr -d ' ')"

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
echo "test-slot の検査に成功しました。"
