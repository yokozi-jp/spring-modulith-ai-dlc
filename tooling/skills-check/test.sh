#!/usr/bin/env bash
# test.sh: 小さなスキルの木を作って skills-check.mjs の終了コードとメッセージを確かめる。
# Run: bash tooling/skills-check/test.sh (task skills-check が本検査の前に実行する)

set -u

dir="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
out="$(mktemp)"
trap 'rm -rf "$tmp" "$out"' EXIT
failures=0

# tree <名前>: 正常な2スキル（foo、bar）の木を $tmp/<名前> に作る
tree() {
  local t="$tmp/$1" name hash entries=""
  for name in foo bar; do
    mkdir -p "$t/.agents/skills/$name" "$t/.kiro/skills"
    printf -- '---\nname: %s\ndescription: d\n---\n' "$name" >"$t/.agents/skills/$name/SKILL.md"
    ln -s "../../.agents/skills/$name" "$t/.kiro/skills/$name"
    hash="$({ printf SKILL.md; cat "$t/.agents/skills/$name/SKILL.md"; } | sha256sum | cut -d' ' -f1)"
    entries="$entries\"$name\":{\"source\":\"o/r\",\"ref\":\"aaa\",\"computedHash\":\"$hash\"},"
  done
  printf '{"version":1,"skills":{%s}}\n' "${entries%,}" >"$t/skills-lock.json"
}

# expect <名前> <終了コード> <メッセージの断片>
expect() {
  local code
  node "$dir/skills-check.mjs" "$tmp/$1" >"$out" 2>&1
  code=$?
  if [ "$code" != "$2" ] || ! grep -q -- "$3" "$out"; then
    echo "FAIL: $1 (exit $code, want $2; fragment '$3')"
    sed 's/^/    /' "$out"
    failures=$((failures + 1))
  fi
}

tree ok
expect ok 0 "skills-check OK: 2 スキル"

tree link-copy
rm "$tmp/link-copy/.kiro/skills/foo" && cp -r "$tmp/link-copy/.agents/skills/foo" "$tmp/link-copy/.kiro/skills/foo"
expect link-copy 1 "foo: .kiro/skills/foo が ../../.agents/skills/foo へのシンボリックリンクではありません"

tree name-mismatch
sed -i 's/^name: foo/name: other/' "$tmp/name-mismatch/.agents/skills/foo/SKILL.md"
expect name-mismatch 1 "foo: SKILL.md の name がディレクトリ名と一致しません"

tree hash-mismatch
echo edited >>"$tmp/hash-mismatch/.agents/skills/foo/SKILL.md"
expect hash-mismatch 1 "foo: 内容ハッシュが skills-lock.json と一致しません"

tree locally-modified
echo edited >>"$tmp/locally-modified/.agents/skills/foo/SKILL.md"
sed -i 's/"foo":{/"foo":{"note":"modified",/' "$tmp/locally-modified/skills-lock.json"
expect locally-modified 0 "skills-check OK"

tree unlocked
mkdir "$tmp/unlocked/.agents/skills/extra"
expect unlocked 1 ".agents/skills/extra が skills-lock.json にありません"

tree ref-split
sed -i 's/"bar":{"source":"o\/r","ref":"aaa"/"bar":{"source":"o\/r","ref":"bbb"/' "$tmp/ref-split/skills-lock.json"
expect ref-split 1 "o/r: ref が複数あります（aaa, bbb）"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
