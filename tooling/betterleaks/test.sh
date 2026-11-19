#!/usr/bin/env bash
# test.sh: .betterleaks.toml の allowlist が除外のつもりのファイルを丸ごと走査から外していないことを、本物の betterleaks で確かめる。
# allowlist の paths に一致するファイルへ乱数の token と password を足して stage し、検出されることを見る（#473）。
# Run: bash tooling/betterleaks/test.sh (task lint-betterleaks が実行する)

set -uo pipefail
. "$(dirname "$0")/../scratch-git/scratch-git.sh" || exit 1

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root" || exit 1
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

# allowlist の paths に一致する追跡ファイルを、設定から求める。
mapfile -t files < <(git ls-files | grep -E -f <(python3 -c '
import tomllib
for a in tomllib.load(open(".betterleaks.toml", "rb"))["allowlists"]:
    print(*a.get("paths", []), sep="\n")
'))
[ "${#files[@]}" -gt 0 ] || { echo "FAIL: allowlist の paths に一致するファイルがありません"; exit 1; }

# 乱数は実行ごとに変える。固定の値だと、betterleaks のエントロピーや stopword の判定で通る値に当たりうる。
random() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"; }
kinds=(token password)
payloads=("ghp_$(random 36)" "password = '$(random 24)'")

repo="$tmp/repo"
git init -q "$repo"
cp .betterleaks.toml .betterleaksignore "$repo/"
for f in "${files[@]}"; do
  mkdir -p "$repo/$(dirname "$f")"
  cp "$f" "$repo/$f"
done

# 終了コード 3 は検出、1 は設定や実行の誤り。誤りを検出と読まない。
scan() { (cd "$repo" && betterleaks git --staged -c .betterleaks.toml --no-banner --redact --exit-code 3 >/dev/null 2>&1); }

git -C "$repo" add -A
scan
if [ $? -eq 0 ]; then
  echo "ok: 現在のファイルは検出されない"
else
  echo "FAIL: 現在のファイルが検出される"
  failures=$((failures + 1))
fi
git -C "$repo" commit -q -m base

for f in "${files[@]}"; do
  for i in "${!payloads[@]}"; do
    payload="${payloads[$i]}"
    printf '\n%s\n' "$payload" >>"$repo/$f"
    git -C "$repo" add "$f"
    scan
    if [ $? -ne 3 ]; then
      echo "FAIL: $f に置いた ${kinds[$i]} が検出されない"
      failures=$((failures + 1))
    else
      echo "ok: $f に置いた ${kinds[$i]} を検出した"
    fi
    git -C "$repo" reset -q --hard
  done
done

[ "$failures" -eq 0 ] || { echo "$failures 件失敗"; exit 1; }
echo "betterleaks の allowlist の検査に成功しました。"
