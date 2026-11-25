#!/usr/bin/env bash
# test.sh: check.sh が、一致を通し、ずれと取り出しの失敗を止めることを確かめる。
# 実行: bash tooling/readme-versions/test.sh（task okf-check が、readme-versions-test として実行する）
set -u

dir="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
failures=0

cat >"$tmp/build.gradle" <<'G'
    id 'org.springframework.boot' version '4.2.0'
    id 'org.jooq.jooq-codegen-gradle' version '3.21.9'
    languageVersion = JavaLanguageVersion.of(25)
    set('springModulithVersion', "2.2.0")
    testImplementation 'com.tngtech.archunit:archunit-junit5:1.5.1'
G
readme() {
  cat >"$tmp/README.md" <<R
| Backend          | Java (Amazon Corretto)     | 25         |   |
| Backend          | Spring Boot                | 4.2.0      |   |
| Backend          | Spring Modulith            | 2.2.0      |   |
| Backend          | jOOQ                       | $1     |   |
| Backend          | ArchUnit                   | 1.5.1      |   |
R
}

# expect <label> <exit code> <output fragment>
expect() {
  local code
  "$dir/check.sh" "$tmp/README.md" "$tmp/build.gradle" >"$tmp/out" 2>&1
  code=$?
  if [ "$code" != "$2" ] || ! grep -qF -- "$3" "$tmp/out"; then
    echo "FAIL: $1 (exit $code, want $2; fragment '$3')"
    sed 's/^/    /' "$tmp/out"
    failures=$((failures + 1))
  fi
}

readme 3.21.9
expect "一致は通る" 0 "OK"
readme 3.21.8
expect "版のずれを止める" 1 "README の jOOQ は「3.21.8」だが、build.gradle は「3.21.9」"
readme 3.21.9
grep -v archunit "$tmp/build.gradle" >"$tmp/build.gradle.new" && mv "$tmp/build.gradle.new" "$tmp/build.gradle"
expect "取り出せない正本を止める" 1 "ArchUnit の版を"

[ "$failures" -eq 0 ] && echo "OK   readme-versions-test"
exit "$failures"
