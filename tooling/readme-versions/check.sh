#!/usr/bin/env bash
# README の技術スタック表の Backend の版が、正本の backend/build.gradle と一致することを確かめる。
# 使い方: tooling/readme-versions/check.sh [README.md] [build.gradle]（リポジトリのどこから実行してもよい）
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
readme="$(realpath "${1:-$root/README.md}")"
gradle="$(realpath "${2:-$root/backend/build.gradle}")"
status=0

# 正本の版を、build.gradle の一行から取り出す。
pick() { sed -nE "$1" "$gradle" | head -n1; }

# check <README の技術名> <正本の版>
check() {
  local name="$1" want="$2" got
  got="$(awk -F'|' -v n="$name" '{ for (i = 1; i <= NF; i++) gsub(/^ +| +$/, "", $i) } $2 == "Backend" && $3 == n { print $4 }' "$readme")"
  if [ -z "$want" ]; then
    echo "FAIL $name の版を $gradle から取り出せない。check.sh の取り出し方を直す" >&2
    status=1
  elif [ "$got" != "$want" ]; then
    echo "FAIL README の $name は「$got」だが、build.gradle は「$want」。README を直す" >&2
    status=1
  fi
}

check "Java (Amazon Corretto)" "$(pick "s/.*JavaLanguageVersion\.of\(([0-9]+)\).*/\1/p")"
check "Spring Boot" "$(pick "s/.*'org\.springframework\.boot' version '([^']+)'.*/\1/p")"
check "Spring Modulith" "$(pick "s/.*springModulithVersion', \"([^\"]+)\".*/\1/p")"
check "jOOQ" "$(pick "s/.*'org\.jooq\.jooq-codegen-gradle' version '([^']+)'.*/\1/p")"
check "ArchUnit" "$(pick "s/.*archunit-junit5:([^']+)'.*/\1/p")"

[ "$status" -eq 0 ] && echo "OK   readme-versions: README の Backend の版は build.gradle と一致している"
exit "$status"
