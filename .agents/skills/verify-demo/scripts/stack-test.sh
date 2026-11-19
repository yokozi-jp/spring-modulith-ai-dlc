#!/usr/bin/env bash
# stack.sh down が、自分の起動でない preview と compose-test を止めず、打ち切られた起動の残りは片付けることを確かめる。
# 検証スタックを動かしていないときに実行する。slot 3 の Compose project を使うので、slot 3 が使用中なら実行しない。使い方: stack-test.sh
set -euo pipefail

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"
STATE=build/verify/stack
STACK="$(dirname "$0")/stack.sh"
TEST_SLOT_UNDER_TEST=3
compose() { COMPOSE_PROJECT_NAME="spring-modulith-test-$TEST_SLOT_UNDER_TEST" docker compose -f docker/compose-test.yml "$@"; }
if [ -d "$STATE" ]; then
  echo "$STATE があります。検証スタックを stack.sh down で片付けてから実行してください。" >&2
  exit 1
fi
if [ -n "$(compose ps -aq 2>/dev/null)" ]; then
  echo "Compose project spring-modulith-test-$TEST_SLOT_UNDER_TEST が動いています。ほかの実行が終わってから実行してください。" >&2
  exit 1
fi

# 背景のジョブは process group の先頭でないので、setsid は fork せずに sleep を新しい group の先頭にする。
setsid sleep 300 < /dev/null > /dev/null 2>&1 &
foreign=$!
cleanup() { kill -- "-$foreign" 2>/dev/null || true; rm -rf "$STATE"; compose down --volumes --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT
sleep 0.5

failures=0
assert() {
  local name="$1"; shift
  if "$@"; then echo "ok: $name"; else echo "FAIL: $name"; failures=$((failures + 1)); fi
}

stale_state() {
  mkdir -p "$STATE"
  echo "$foreign" > "$STATE/preview.pid"
  echo "$1" > "$STATE/boot-id"
  echo "$2" > "$STATE/preview.start"
  echo "not-a-container-of-this-run" > "$STATE/containers"
  echo "$TEST_SLOT_UNDER_TEST" > "$STATE/slot"
}

run_down() { "$STACK" down > /tmp/stack-test-down.log 2>&1; }
alive() { kill -0 "$foreign" 2>/dev/null; }
no_state() { [ ! -d "$STATE" ]; }
no_containers() { [ -z "$(compose ps -aq)" ]; }
start="$(sed 's/.*) //' "/proc/$foreign/stat" | cut -d' ' -f20)"
boot="$(cat /proc/sys/kernel/random/boot_id)"

stale_state "00000000-0000-0000-0000-000000000000" "$start"
assert "前の起動の PID の記録で down が成功する" run_down
assert "前の起動の PID の記録では、その番号の process group を止めない" alive
assert "down が状態のディレクトリを消す" no_state

stale_state "$boot" "1"
assert "起動時刻の違う PID の記録で down が成功する" run_down
assert "起動時刻の違う PID の記録では、その番号の process group を止めない" alive

# 起動時刻と boot ID が合っても、vp preview でなければ止めない。
stale_state "$boot" "$start"
assert "vp preview でない PID の記録で down が成功する" run_down
assert "vp preview でない process group を止めない" alive

# slot を取る前に打ち切られた起動には slot の記録がなく、片付けるものもない。
stale_state "$boot" "1"
rm "$STATE/slot"
assert "slot の記録がなくても down が成功する" run_down
assert "slot の記録がなければ状態のディレクトリだけを消す" no_state

# ほかの実行が立て直した compose-test は、記録と違うので消さない。
compose up -d --wait redis > /dev/null 2>&1
other="$(compose ps -aq)"
stale_state "$boot" "1"
others_kept() { [ "$(compose ps -aq)" = "$other" ]; }
assert "記録と違う compose-test があっても down が成功する" run_down
assert "記録と違う compose-test を止めない" others_kept
compose down --volumes --remove-orphans > /dev/null 2>&1

# 起動が打ち切られ、コンテナの記録がないとき。状態の後に作られたコンテナは、その起動のものとして片付ける。
stale_state "$boot" "1"
rm "$STATE/containers"
compose up -d --wait redis > /dev/null 2>&1
assert "コンテナの記録がなくても down が成功する" run_down
assert "打ち切られた起動のコンテナを片付ける" no_containers

# 状態より前から動いているコンテナは、記録がなくてもほかの実行のものとして残す。
compose up -d --wait redis > /dev/null 2>&1
other="$(compose ps -aq)"
sleep 1
stale_state "$boot" "1"
rm "$STATE/containers"
assert "状態より前のコンテナがあっても down が成功する" run_down
assert "状態より前のコンテナを止めない" others_kept

if [ "$failures" -ne 0 ]; then
  echo "$failures 件が失敗しました。/tmp/stack-test-down.log を確かめてください。" >&2
  exit 1
fi
echo "all passed"
