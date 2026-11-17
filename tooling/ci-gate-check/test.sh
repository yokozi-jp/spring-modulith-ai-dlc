#!/usr/bin/env bash
# test.sh: 小さな workflow の木を作って、ci-gate-check.py と gate.sh の終了コードとメッセージを確かめる。
# Run: bash tooling/ci-gate-check/test.sh (task ci-gate-check が本検査の前に実行する)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
out="$(mktemp)"
trap 'rm -rf "$tmp" "$out"' EXIT
failures=0

# expect <名前> <終了コード> <出力に含まれる文字列> <コマンド...>
expect() {
  local name="$1" code="$2" needle="$3"
  shift 3
  "$@" > "$out" 2>&1
  local actual=$?
  if [ "$actual" -ne "$code" ] || ! grep -qF -- "$needle" "$out"; then
    echo "FAIL: $name (終了コード $actual、期待 $code、期待する出力: $needle)"
    sed 's/^/  | /' "$out"
    failures=$((failures + 1))
  else
    echo "ok: $name"
  fi
}

# 通る木を作る。ケースごとにコピーして一か所だけ壊す。
base="$tmp/base"
mkdir -p "$base"
cat > "$base/ci.yml" <<'YAML'
name: CI
on:
  pull_request:
jobs:
  backend:
    uses: ./.github/workflows/backend.yml
  gate:
    if: always()
    needs: [backend]
    runs-on: ubuntu-latest
    steps:
      - run: "true"
YAML
cat > "$base/backend.yml" <<'YAML'
name: Backend
on:
  push:
  pull_request:
  workflow_call:
concurrency:
  group: backend-${{ github.event_name == 'push' && github.ref || github.run_id }}
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - run: "true"
YAML
cat > "$base/e2e.yml" <<'YAML'
name: E2E
on:
  pull_request:
jobs:
  e2e:
    runs-on: ubuntu-latest
    steps:
      - run: "true"
YAML
printf '# 理由つきの除外\ne2e.yml\t必須にしない\n' > "$tmp/excluded.tsv"

# case <名前>: base を複製した木のパスを返す
case_dir() {
  rm -rf "$tmp/$1" && cp -r "$base" "$tmp/$1"
  echo "$tmp/$1"
}
check() { python3 "$dir/ci-gate-check.py" "$1" "${2:-$tmp/excluded.tsv}"; }

expect "揃った木は通る" 0 "ci-gate-check OK" check "$base"

d="$(case_dir missing-needs)"
sed -i 's/needs: \[backend\]/needs: []/' "$d/ci.yml"
expect "gate の needs にないジョブを見つける" 1 "backend が gate の needs にありません" check "$d"

d="$(case_dir no-always)"
sed -i '/if: always()/d' "$d/ci.yml"
expect "gate の if: always() がないと失敗" 1 "if: always() がありません" check "$d"

d="$(case_dir uncalled)"
cp "$d/e2e.yml" "$d/lint.yml"
expect "呼ばれず除外もない pull_request の workflow を見つける" 1 "lint.yml は pull_request で動きますが" check "$d"

d="$(case_dir not-callable)"
sed -i '/workflow_call:/d' "$d/backend.yml"
expect "呼ばれる側に workflow_call がないと失敗" 1 "backend.yml に workflow_call がありません" check "$d"

d="$(case_dir shared-group)"
sed -i 's/group: .*/group: ${{ github.workflow }}-${{ github.ref }}/' "$d/backend.yml"
expect "concurrency の group が github.workflow を使うと失敗" 1 "github.workflow を使っています" check "$d"

printf 'e2e.yml\t必須にしない\nbackend.yml\t呼ばれているのに除外\n' > "$tmp/stale.tsv"
expect "呼ばれている workflow が除外にあると失敗" 1 "backend.yml は ci.yml から呼ばれている" check "$base" "$tmp/stale.tsv"

printf 'e2e.yml\t必須にしない\ngone.yml\t消えた\n' > "$tmp/gone.tsv"
expect "存在しない workflow が除外にあると失敗" 1 "gone.yml がありません" check "$base" "$tmp/gone.tsv"

printf 'e2e.yml\n' > "$tmp/no-reason.tsv"
expect "理由のない除外は失敗" 1 "理由のない行" check "$base" "$tmp/no-reason.tsv"

expect "実際の ci.yml が通る" 0 "ci-gate-check OK" python3 "$dir/ci-gate-check.py" "$dir/../../.github/workflows"

gate() { printf '%s' "$1" | bash "$dir/gate.sh"; }
expect "gate は全部 success なら通る" 0 "2 件" gate '{"a":{"result":"success"},"b":{"result":"success"}}'
expect "gate は skipped を失敗にする" 1 "b: skipped" gate '{"a":{"result":"success"},"b":{"result":"skipped"}}'
expect "gate は failure を失敗にする" 1 "a: failure" gate '{"a":{"result":"failure"}}'
expect "gate は cancelled を失敗にする" 1 "a: cancelled" gate '{"a":{"result":"cancelled"}}'
expect "gate は needs が空なら失敗にする" 1 "needs が空" gate '{}'

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
echo "all passed"
