#!/usr/bin/env bash
# test.sh: check.sh を一時のリポジトリで検査する。
# 実行: bash tooling/removed-names/test.sh（task removed-names-test が実行する）
set -u

dir="$(cd "$(dirname "$0")" && pwd)"
# 一時のリポジトリへの git を、本物のリポジトリと利用者の git の設定から切り離す（task lint-scratch-git が source の位置を検査する）。
. "$dir/../scratch-git/scratch-git.sh" || exit 1
tmp="$(mktemp -d)"
out="$tmp/out"
trap 'rm -rf "$tmp"' EXIT
failures=0

# expect <label> <exit code> <output fragment>
expect() {
  local label="$1" want="$2" fragment="$3" code
  (cd "$tmp/repo" && "$dir/check.sh" "$tmp/names.txt") >"$out" 2>&1
  code=$?
  if [ "$code" != "$want" ] || ! grep -qF -- "$fragment" "$out"; then
    echo "FAIL: $label (exit $code, want $want; fragment '$fragment')"
    sed 's/^/    /' "$out"
    failures=$((failures + 1))
  fi
}

mkdir -p "$tmp/repo/docs/adr" "$tmp/repo/docs/backend" "$tmp/repo/.agents" "$tmp/repo/backend/src/generated"
git -C "$tmp/repo" init -q
printf '# 履歴\nOldWriter を使っていた。\n' >"$tmp/repo/docs/adr/ADR-001-history.md"
printf '# 規約\nNewWriter を使う。\n' >"$tmp/repo/docs/backend/rule.md"
printf 'OldWriter\n' >"$tmp/repo/backend/src/generated/Gen.java"
printf '# コメントの行\nOldWriter\t^docs/adr/ADR-001-\n' >"$tmp/names.txt"
git -C "$tmp/repo" add -A

expect "履歴のパスと生成コードに残る旧名は許す" 0 "OK   removed-names"

printf '# 規約\nOldWriter を使う。\n' >"$tmp/repo/docs/backend/rule.md"
git -C "$tmp/repo" add -A
expect "規約に残った旧名は失敗にする" 1 "削除した識別子 OldWriter が残っている"
expect "失敗の出力に残った場所を出す" 1 "docs/backend/rule.md:2"

printf '# 規約\nOldWriterXY は別の名前なので、語の境界で一致しない。\n' >"$tmp/repo/docs/backend/rule.md"
git -C "$tmp/repo" add -A
expect "別の識別子の一部は一致させない" 0 "OK   removed-names"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
