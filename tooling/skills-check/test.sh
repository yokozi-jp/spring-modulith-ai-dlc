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

tree frontmatter-split
printf -- '---\nname: foo\ndescription: d。\n続き。\n---\n' >"$tmp/frontmatter-split/.agents/skills/foo/SKILL.md"
sed -i 's/"foo":{/"foo":{"note":"modified",/' "$tmp/frontmatter-split/skills-lock.json"
expect frontmatter-split 1 "foo: SKILL.md のフロントマターを YAML として読めません（4 行目が"

tree local-ok
sed -i 's/"foo":{"source":"o\/r","ref":"aaa"/"foo":{"source":"local","sourceType":"local","note":"authored"/' "$tmp/local-ok/skills-lock.json"
expect local-ok 0 "skills-check OK"

tree local-no-note
sed -i 's/"foo":{"source":"o\/r","ref":"aaa"/"foo":{"source":"local","sourceType":"local"/' "$tmp/local-no-note/skills-lock.json"
expect local-no-note 1 "foo: 取得元のないスキル（sourceType が local）には note が必要です"

tree unlocked
mkdir "$tmp/unlocked/.agents/skills/extra"
expect unlocked 1 ".agents/skills/extra が skills-lock.json にありません"

tree ref-split
sed -i 's/"bar":{"source":"o\/r","ref":"aaa"/"bar":{"source":"o\/r","ref":"bbb"/' "$tmp/ref-split/skills-lock.json"
expect ref-split 1 "o/r: ref が複数あります（aaa, bbb）"

# plugin <名前>: 正常な木に、kiro/install.sh が入れた形のスキル baz を足す
plugin() {
  local t="$tmp/$1" hash
  tree "$1"
  mkdir -p "$t/.kiro/skills/baz"
  printf -- '---\nname: baz\ndescription: d\n---\n' >"$t/.kiro/skills/baz/SKILL.md"
  hash="$({ printf SKILL.md; cat "$t/.kiro/skills/baz/SKILL.md"; } | sha256sum | cut -d' ' -f1)"
  printf '# cursor-plugins 0123abc\nskills/baz\t%s\n' "$hash" >"$t/.kiro/.kiro-plugins-installed"
}

# content_hash <パス>: kiro/install.sh の content_hash と同じ手順（バイト順に並べたパスと内容のハッシュ）
content_hash() (
  if [ -d "$1" ]; then
    cd "$1" && find . -type f | sed 's#^\./##' | LC_ALL=C sort | while IFS= read -r f; do printf '%s' "$f"; cat -- "$f"; done
  else
    cat -- "$1"
  fi | sha256sum | cut -d' ' -f1
)

# record <名前> <.kiro からのパス>: 記録にそのパスと現在のハッシュを足す
record() {
  printf '%s\t%s\n' "$2" "$(content_hash "$tmp/$1/.kiro/$2")" >>"$tmp/$1/.kiro/.kiro-plugins-installed"
}

plugin plugin-ok
expect plugin-ok 0 "skills-check OK: 2 スキル、kiro/install.sh で入れた 1 スキル"

plugin plugin-edited
echo edited >>"$tmp/plugin-edited/.kiro/skills/baz/SKILL.md"
expect plugin-edited 1 ".kiro/skills/baz: 内容ハッシュが .kiro/.kiro-plugins-installed と一致しません"

plugin plugin-no-hash
printf '# cursor-plugins 0123abc\nskills/baz\n' >"$tmp/plugin-no-hash/.kiro/.kiro-plugins-installed"
expect plugin-no-hash 1 ".kiro/skills/baz: .kiro/.kiro-plugins-installed に内容ハッシュがありません"

plugin plugin-missing
rm -r "$tmp/plugin-missing/.kiro/skills/baz"
expect plugin-missing 1 ".kiro/skills/baz: .kiro/.kiro-plugins-installed にありますが、ファイルがありません"

plugin plugin-both
printf 'skills/foo\tx\n' >>"$tmp/plugin-both/.kiro/.kiro-plugins-installed"
expect plugin-both 1 "foo: skills-lock.json と .kiro/.kiro-plugins-installed の両方にあります"

plugin plugin-no-commit
sed -i 1d "$tmp/plugin-no-commit/.kiro/.kiro-plugins-installed"
expect plugin-no-commit 1 "先頭に「# cursor-plugins <コミット>」の行が1つ必要です"

plugin plugin-dirty
sed -i '1s/.*/# cursor-plugins 0123abc-dirty/' "$tmp/plugin-dirty/.kiro/.kiro-plugins-installed"
expect plugin-dirty 1 "未コミットの変更を含みます"

plugin plugin-outside
mkdir "$tmp/plugin-outside/tooling" && echo x >"$tmp/plugin-outside/tooling/a"
record plugin-outside ../tooling
expect plugin-outside 1 "../tooling は skills、agents、steering の直下ではありません"

plugin plugin-node-modules
mkdir "$tmp/plugin-node-modules/.kiro/skills/baz/node_modules" && echo x >"$tmp/plugin-node-modules/.kiro/skills/baz/node_modules/m.js"
expect plugin-node-modules 0 "skills-check OK"

plugin plugin-symlink
ln -s /etc/hostname "$tmp/plugin-symlink/.kiro/skills/baz/link"
expect plugin-symlink 1 ".kiro/skills/baz/link: シンボリックリンクは認められません"

plugin plugin-byte-order
printf b >"$tmp/plugin-byte-order/.kiro/skills/baz/B.md" && printf a >"$tmp/plugin-byte-order/.kiro/skills/baz/a.md"
sed -i '/^skills\/baz/d' "$tmp/plugin-byte-order/.kiro/.kiro-plugins-installed"
record plugin-byte-order skills/baz
expect plugin-byte-order 0 "skills-check OK"

plugin plugin-agent-file
mkdir "$tmp/plugin-agent-file/.kiro/agents" && echo ok >"$tmp/plugin-agent-file/.kiro/agents/x.md"
record plugin-agent-file agents/x.md
expect plugin-agent-file 0 "skills-check OK"
echo edited >>"$tmp/plugin-agent-file/.kiro/agents/x.md"
expect plugin-agent-file 1 ".kiro/agents/x.md: 内容ハッシュが .kiro/.kiro-plugins-installed と一致しません"

plugin plugin-agent-unlisted
mkdir "$tmp/plugin-agent-unlisted/.kiro/agents" && echo ok >"$tmp/plugin-agent-unlisted/.kiro/agents/x.md"
expect plugin-agent-unlisted 1 ".kiro/agents/x.md が .kiro/.kiro-plugins-installed にありません"

plugin plugin-steering-unlisted
mkdir "$tmp/plugin-steering-unlisted/.kiro/steering" && echo ok >"$tmp/plugin-steering-unlisted/.kiro/steering/kiro-adapter.md"
expect plugin-steering-unlisted 1 ".kiro/steering/kiro-adapter.md が .kiro/.kiro-plugins-installed にありません"

plugin plugin-unknown-dir
mkdir "$tmp/plugin-unknown-dir/.kiro/skills/extra"
expect plugin-unknown-dir 1 ".kiro/skills/extra が skills-lock.json にも .kiro/.kiro-plugins-installed にもありません"

if [ "$failures" -gt 0 ]; then
  echo "$failures failure(s)"
  exit 1
fi
echo "all passed"
