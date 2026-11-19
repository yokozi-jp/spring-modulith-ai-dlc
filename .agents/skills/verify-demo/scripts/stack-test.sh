#!/usr/bin/env bash
# stack.sh down が、自分の起動でない preview と compose-test を止めないことを確かめる。
# 検証スタックを動かしていないときに実行する。使い方: stack-test.sh
set -euo pipefail

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"
STATE=build/verify/stack
STACK="$(dirname "$0")/stack.sh"
if [ -d "$STATE" ]; then
  echo "$STATE があります。検証スタックを stack.sh down で片付けてから実行してください。" >&2
  exit 1
fi

failures=0
assert() {
  local name="$1"; shift
  if "$@"; then echo "ok: $name"; else echo "FAIL: $name"; failures=$((failures + 1)); fi
}

# 無関係の process group の先頭。PID の記録が古くなり、別のプロセスが同じ番号を使った状態を作る。
# 背景のジョブは process group の先頭でないので、setsid は fork せずに sleep を新しい group の先頭にする。
setsid sleep 300 < /dev/null > /dev/null 2>&1 &
foreign=$!
sleep 0.5
compose() { docker compose -f docker/compose-test.yml "$@"; }
if [ -n "$(compose ps -aq 2>/dev/null)" ]; then
  echo "Compose project spring-modulith-test が動いています。ほかの実行が終わってから実行してください。" >&2
  exit 1
fi
cleanup() { kill -- "-$foreign" 2>/dev/null || true; rm -rf "$STATE"; compose down --volumes --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

stale_state() {
  mkdir -p "$STATE"
  echo "$foreign" > "$STATE/preview.pid"
  echo "$1" > "$STATE/boot-id"
  echo "$2" > "$STATE/preview.start"
  echo "not-a-container-of-this-run" > "$STATE/containers"
}

run_down() { "$STACK" down > /tmp/stack-test-down.log 2>&1; }
alive() { kill -0 "$foreign" 2>/dev/null; }
no_state() { [ ! -d "$STATE" ]; }
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

# ほかの実行が立て直した compose-test は、記録と違うので消さない。
compose up -d --wait redis > /dev/null 2>&1
other="$(compose ps -aq)"
stale_state "$boot" "1"
others_kept() { [ "$(compose ps -aq)" = "$other" ]; }
assert "記録と違う compose-test があっても down が成功する" run_down
assert "記録と違う compose-test を止めない" others_kept

if [ "$failures" -ne 0 ]; then
  echo "$failures 件が失敗しました。/tmp/stack-test-down.log を確かめてください。" >&2
  exit 1
fi
echo "all passed"
